import Foundation
import WebKit

@MainActor
public final class HeadlessGoogleWebViewSearcher: NSObject, WKNavigationDelegate {
    private var webView: WKWebView?
    private var activeSearchId: UUID?
    private var activeContinuation: CheckedContinuation<[ImageResult], Never>?
    private var currentForceGifHint: Bool = false
    private var currentForceTransparentHint: Bool = false
    private var currentFallbackBingUrl: URL?
    private var didFallbackToBing: Bool = false
    private var googleRateLimitedUntil: Date = .distantPast
    private var timeoutWorkItem: DispatchWorkItem?
    private var check1WorkItem: DispatchWorkItem?
    private var check2WorkItem: DispatchWorkItem?

    public override init() {
        super.init()
    }

    private func getOrCreateWebView() -> WKWebView {
        if let existing = webView {
            return existing
        }
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        let wv = WKWebView(frame: NSRect(x: 0, y: 0, width: 1280, height: 800), configuration: config)
        wv.navigationDelegate = self
        wv.customUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
        self.webView = wv
        return wv
    }

    public func searchGoogleImages(
        query: String,
        filter: SearchFilter,
        nsfwMode: Bool = false,
        preferBingWebView: Bool = false
    ) async -> [ImageResult] {
        let effectiveQuery = filter.buildEffectiveQuery(query)
        guard !effectiveQuery.isEmpty,
              let encoded = effectiveQuery.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            return []
        }

        let animatedRequested = filter.requiresAnimatedGif(for: query)

        // Build Google Images URL
        let tbsParam: String
        if animatedRequested {
            tbsParam = "&tbs=itp:animated"
        } else if filter.transparentOnly {
            tbsParam = "&tbs=ic:trans"
        } else {
            tbsParam = ""
        }
        let safeParam = nsfwMode ? "off" : "active"
        let googleUrlString = "https://www.google.com/search?q=\(encoded)&udm=2&hl=en&safe=\(safeParam)\(tbsParam)"

        // Build Bing Images WebView URL (never poisoned when loaded in a real WKWebView!)
        let qftParam: String
        if animatedRequested {
            qftParam = "&qft=+filterui:photo-animatedgif"
        } else if filter.transparentOnly {
            qftParam = "&qft=+filterui:photo-transparent"
        } else {
            qftParam = ""
        }
        let bingUrlString = "https://www.bing.com/images/search?q=\(encoded)&form=HDRSC2\(qftParam)"

        guard let googleUrl = URL(string: googleUrlString),
              let bingUrl = URL(string: bingUrlString) else {
            return []
        }

        // Cancel any previous pending continuation before starting a new search
        finishCurrentContinuation(with: [])

        let searchId = UUID()
        self.activeSearchId = searchId

        let shouldStartWithBing = preferBingWebView || Date() < googleRateLimitedUntil
        let initialUrl = shouldStartWithBing ? bingUrl : googleUrl

        return await withCheckedContinuation { continuation in
            self.activeContinuation = continuation
            self.currentForceGifHint = animatedRequested
            self.currentForceTransparentHint = filter.transparentOnly
            self.currentFallbackBingUrl = bingUrl
            self.didFallbackToBing = shouldStartWithBing

            let wv = getOrCreateWebView()
            wv.stopLoading()
            // Clear any previous search DOM before navigating so stale results can never be scraped
            wv.evaluateJavaScript("if (document.body) { document.body.innerHTML = ''; }", completionHandler: nil)

            self.scheduleTimeout(for: searchId, delay: 4.5)

            var request = URLRequest(url: initialUrl)
            request.timeoutInterval = 4.2
            request.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
            wv.load(request)
        }
    }

    private func scheduleTimeout(for searchId: UUID, delay: TimeInterval = 4.5) {
        timeoutWorkItem?.cancel()
        let timeout = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                guard let self = self, self.activeSearchId == searchId else { return }
                self.extractAndFinish(searchId: searchId, forceComplete: true)
            }
        }
        timeoutWorkItem = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: timeout)
    }

    public func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard let url = navigationAction.request.url,
              let scheme = url.scheme?.lowercased() else {
            decisionHandler(.cancel)
            return
        }
        if scheme == "about" {
            decisionHandler(.allow)
            return
        }
        if (scheme == "http" || scheme == "https"),
           SearchParsers.isSafeHttpUrl(url.absoluteString) {
            decisionHandler(.allow)
            return
        }
        decisionHandler(.cancel)
    }

    public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard webView === self.webView, let searchId = self.activeSearchId else { return }

        // If Google redirected to /sorry/index (rate-limit CAPTCHA), immediately switch to Bing Images WebView!
        if let currentUrlStr = webView.url?.absoluteString,
           currentUrlStr.contains("google.com/sorry"),
           !didFallbackToBing,
           let fallbackUrl = currentFallbackBingUrl {
            googleRateLimitedUntil = Date().addingTimeInterval(600)
            didFallbackToBing = true
            check1WorkItem?.cancel()
            check2WorkItem?.cancel()
            scheduleTimeout(for: searchId, delay: 4.5)
            var req = URLRequest(url: fallbackUrl)
            req.timeoutInterval = 4.0
            req.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
            webView.load(req)
            return
        }

        check1WorkItem?.cancel()
        check2WorkItem?.cancel()

        let w1 = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                guard let self = self, self.activeSearchId == searchId else { return }
                self.extractAndFinish(searchId: searchId, forceComplete: false)
            }
        }
        check1WorkItem = w1
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.20, execute: w1)

        let w2 = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                guard let self = self, self.activeSearchId == searchId else { return }
                self.extractAndFinish(searchId: searchId, forceComplete: true)
            }
        }
        check2WorkItem = w2
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.85, execute: w2)
    }

    public func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        guard webView === self.webView, let searchId = self.activeSearchId else { return }
        if !didFallbackToBing, let fallbackUrl = currentFallbackBingUrl {
            didFallbackToBing = true
            scheduleTimeout(for: searchId, delay: 4.5)
            webView.load(URLRequest(url: fallbackUrl))
            return
        }
        finishCurrentContinuation(with: [])
    }

    public func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        guard webView === self.webView, let searchId = self.activeSearchId else { return }
        if !didFallbackToBing, let fallbackUrl = currentFallbackBingUrl {
            didFallbackToBing = true
            scheduleTimeout(for: searchId, delay: 4.5)
            webView.load(URLRequest(url: fallbackUrl))
            return
        }
        finishCurrentContinuation(with: [])
    }

    private func extractAndFinish(searchId: UUID, forceComplete: Bool) {
        guard self.activeSearchId == searchId,
              activeContinuation != nil,
              let wv = webView else { return }
        let gifHint = currentForceGifHint
        let transHint = currentForceTransparentHint

        wv.evaluateJavaScript(Self.extractionJS) { [weak self] result, _ in
            Task { @MainActor in
                guard let self = self,
                      self.activeSearchId == searchId,
                      self.activeContinuation != nil else { return }
                let rawJson = (result as? String) ?? ""
                let parsed = SearchParsers.parseGoogleWebViewJson(rawJson, forceGifHint: gifHint, forceTransparentHint: transHint)
                if !parsed.isEmpty {
                    self.finishCurrentContinuation(with: parsed)
                } else if forceComplete {
                    // If Google returned 0 items without redirecting to /sorry, fallback to Bing WebView once!
                    if !self.didFallbackToBing, let fallbackUrl = self.currentFallbackBingUrl, let wv = self.webView {
                        self.googleRateLimitedUntil = Date().addingTimeInterval(300)
                        self.didFallbackToBing = true
                        self.scheduleTimeout(for: searchId, delay: 4.5)
                        wv.load(URLRequest(url: fallbackUrl))
                        return
                    }
                    self.finishCurrentContinuation(with: [])
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

        if let cont = activeContinuation {
            activeContinuation = nil
            cont.resume(returning: results)
        }
    }

    public static let extractionJS = """
    (function() {
        var results = [];
        var seen = {};
        function addItem(thumb, full, title, srcBadge) {
            var key = full || thumb;
            if (!key || key.indexOf('http') !== 0 || seen[key]) return;
            if (key.indexOf('google.com') !== -1 || key.indexOf('gstatic.com/kp') !== -1) return;
            seen[key] = true;
            results.push({
                thumb: thumb || full,
                full: full || thumb,
                title: title || 'Image',
                isGif: (full || '').toLowerCase().indexOf('.gif') !== -1,
                source: srcBadge || 'G'
            });
        }
        try {
            if (location.host.indexOf('bing.com') !== -1) {
                var cards = document.querySelectorAll('a.iusc');
                for (var i = 0; i < cards.length && results.length < 48; i++) {
                    var mAttr = cards[i].getAttribute('m');
                    if (!mAttr) continue;
                    try {
                        var obj = JSON.parse(mAttr);
                        if (obj && obj.murl && obj.murl.indexOf('http') === 0) {
                            addItem(obj.turl || obj.murl, obj.murl, obj.t || 'Image', 'G');
                        }
                    } catch (e) {}
                }
                return JSON.stringify(results);
            }
        } catch (e) {}
        try {
            var html = document.documentElement.innerHTML;
            var pairRegex = /\\["(https:\\/\\/encrypted-tbn0\\.gstatic\\.com\\/images\\?q=tbn:[^"]+)",\\d+,\\d+\\]\\s*,\\s*\\["(https?:\\/\\/[^"]+)",\\d+,\\d+\\]/g;
            var m;
            while ((m = pairRegex.exec(html)) !== null && results.length < 45) {
                var tUrl = m[1].replace(/\\\\u003d/g, '=').replace(/\\\\u0026/g, '&');
                var fUrl = m[2].replace(/\\\\u003d/g, '=').replace(/\\\\u0026/g, '&');
                if (fUrl.indexOf('gstatic.com') === -1) {
                    addItem(tUrl, fUrl, 'Google Image', 'G');
                }
            }
        } catch (e) {}
        return JSON.stringify(results);
    })();
    """
}
