import Foundation
import WebKit

@MainActor
public final class HeadlessGoogleWebViewSearcher: NSObject, WKNavigationDelegate {
    private var webView: WKWebView?
    private var activeSearchId: UUID?
    private var activeContinuation: CheckedContinuation<[ImageResult], Never>?
    private var currentForceGifHint: Bool = false
    private var timeoutWorkItem: DispatchWorkItem?
    private var check1WorkItem: DispatchWorkItem?
    private var check2WorkItem: DispatchWorkItem?

    public override init() {
        super.init()
    }

    /// Creates a brand-new, clean WKWebView for each search so a previous search's DOM can never leak into a new search.
    private func createFreshWebView() -> WKWebView {
        if let old = webView {
            old.stopLoading()
            old.navigationDelegate = nil
            webView = nil
        }
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        let wv = WKWebView(frame: NSRect(x: 0, y: 0, width: 1024, height: 768), configuration: config)
        wv.navigationDelegate = self
        wv.customUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
        self.webView = wv
        return wv
    }

    public func searchGoogleImages(
        query: String,
        filter: SearchFilter,
        nsfwMode: Bool = false
    ) async -> [ImageResult] {
        let effectiveQuery = filter.buildEffectiveQuery(query)
        guard !effectiveQuery.isEmpty,
              let encoded = effectiveQuery.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            return []
        }

        let animatedRequested = filter.requiresAnimatedGif(for: query)
        let tbsParam: String
        if animatedRequested {
            tbsParam = "&tbs=itp:animated"
        } else if filter.transparentOnly {
            tbsParam = "&tbs=ic:trans"
        } else {
            tbsParam = ""
        }
        let safeParam = nsfwMode ? "off" : "active"
        let urlString = "https://www.google.com/search?q=\(encoded)&udm=2&hl=en&safe=\(safeParam)\(tbsParam)"
        guard let url = URL(string: urlString) else { return [] }

        // Cancel any previous pending continuation before starting a new search
        finishCurrentContinuation(with: [])

        let searchId = UUID()
        self.activeSearchId = searchId

        return await withCheckedContinuation { continuation in
            self.activeContinuation = continuation
            self.currentForceGifHint = animatedRequested

            let wv = createFreshWebView()

            let timeout = DispatchWorkItem { [weak self] in
                Task { @MainActor in
                    guard let self = self, self.activeSearchId == searchId else { return }
                    self.extractAndFinish(searchId: searchId, forceComplete: true)
                }
            }
            self.timeoutWorkItem = timeout
            DispatchQueue.main.asyncAfter(deadline: .now() + 4.5, execute: timeout)

            var request = URLRequest(url: url)
            request.timeoutInterval = 4.2
            request.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
            wv.load(request)
        }
    }

    public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard webView === self.webView, let searchId = self.activeSearchId else { return }

        check1WorkItem?.cancel()
        check2WorkItem?.cancel()

        let w1 = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                guard let self = self, self.activeSearchId == searchId else { return }
                self.extractAndFinish(searchId: searchId, forceComplete: false)
            }
        }
        check1WorkItem = w1
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35, execute: w1)

        let w2 = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                guard let self = self, self.activeSearchId == searchId else { return }
                self.extractAndFinish(searchId: searchId, forceComplete: true)
            }
        }
        check2WorkItem = w2
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.10, execute: w2)
    }

    public func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        guard webView === self.webView else { return }
        finishCurrentContinuation(with: [])
    }

    public func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        guard webView === self.webView else { return }
        finishCurrentContinuation(with: [])
    }

    private func extractAndFinish(searchId: UUID, forceComplete: Bool) {
        guard self.activeSearchId == searchId,
              activeContinuation != nil,
              let wv = webView else { return }
        let gifHint = currentForceGifHint

        wv.evaluateJavaScript(Self.extractionJS) { [weak self] result, _ in
            Task { @MainActor in
                guard let self = self,
                      self.activeSearchId == searchId,
                      self.activeContinuation != nil else { return }
                let rawJson = (result as? String) ?? ""
                let parsed = SearchParsers.parseGoogleWebViewJson(rawJson, forceGifHint: gifHint)
                if !parsed.isEmpty || forceComplete {
                    self.finishCurrentContinuation(with: parsed)
                }
            }
        }
    }

    private func finishCurrentContinuation(with results: [ImageResult]) {
        timeoutWorkItem?.cancel()
        check1WorkItem?.cancel()
        check2WorkItem?.cancel()
        timeoutWorkItem = nil
        check1WorkItem = nil
        check2WorkItem = nil
        activeSearchId = nil

        if let old = webView {
            old.stopLoading()
            old.navigationDelegate = nil
            webView = nil
        }

        if let cont = activeContinuation {
            activeContinuation = nil
            cont.resume(returning: results)
        }
    }

    public static let extractionJS = """
    (function() {
        var results = [];
        var seen = {};
        function addItem(thumb, full, title) {
            var key = full || thumb;
            if (!key || key.indexOf('http') !== 0 || seen[key]) return;
            if (key.indexOf('google.com') !== -1 || key.indexOf('gstatic.com/kp') !== -1) return;
            seen[key] = true;
            results.push({
                thumb: thumb || full,
                full: full || thumb,
                title: title || 'Google Image',
                isGif: (full || '').toLowerCase().indexOf('.gif') !== -1
            });
        }
        try {
            var html = document.documentElement.innerHTML;
            var pairRegex = /\\["(https:\\/\\/encrypted-tbn0\\.gstatic\\.com\\/images\\?q=tbn:[^"]+)",\\d+,\\d+\\]\\s*,\\s*\\["(https?:\\/\\/[^"]+)",\\d+,\\d+\\]/g;
            var m;
            while ((m = pairRegex.exec(html)) !== null && results.length < 40) {
                var tUrl = m[1].replace(/\\\\u003d/g, '=').replace(/\\\\u0026/g, '&');
                var fUrl = m[2].replace(/\\\\u003d/g, '=').replace(/\\\\u0026/g, '&');
                if (fUrl.indexOf('gstatic.com') === -1) {
                    addItem(tUrl, fUrl, 'Google Image');
                }
            }
        } catch (e) {}
        return JSON.stringify(results);
    })();
    """
}
