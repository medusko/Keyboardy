import Foundation

@MainActor
public final class ImageSearchRepository {
    private let headlessGoogleSearcher: HeadlessGoogleWebViewSearcher

    public init(headlessGoogleSearcher: HeadlessGoogleWebViewSearcher) {
        self.headlessGoogleSearcher = headlessGoogleSearcher
    }

    public func searchImages(
        query: String,
        filter: SearchFilter,
        engineMode: SearchEngineMode,
        nsfwMode: Bool,
        optionalApiKey: String,
        onProgressiveResults: @escaping @MainActor ([ImageResult]) -> Void
    ) async -> [ImageResult] {
        let effectiveQuery = filter.buildEffectiveQuery(query)
        guard !effectiveQuery.isEmpty else { return [] }

        async let apiResultsTask: [ImageResult] = optionalApiKey.isEmpty
            ? []
            : Self.fetchOptionalApiResults(
                effectiveQuery: effectiveQuery,
                filter: filter,
                nsfwMode: nsfwMode,
                apiKey: optionalApiKey
            )

        switch engineMode {
        case .instantHttp:
            async let fastTask = Self.fetchInstantHttpImages(
                effectiveQuery: effectiveQuery,
                filter: filter,
                nsfwMode: nsfwMode
            )
            let fast = await fastTask
            let apiItems = await apiResultsTask
            let merged = Self.mergeUnique(apiItems, fast)
            onProgressiveResults(merged)
            return merged

        case .googleWebView:
            let googleItems = await headlessGoogleSearcher.searchGoogleImages(
                query: query,
                filter: filter,
                nsfwMode: nsfwMode
            )
            let finalItems: [ImageResult]
            if !googleItems.isEmpty {
                finalItems = googleItems
            } else {
                finalItems = await Self.fetchInstantHttpImages(
                    effectiveQuery: effectiveQuery,
                    filter: filter,
                    nsfwMode: nsfwMode
                )
            }
            onProgressiveResults(finalItems)
            return finalItems

        case .hybrid:
            async let instantTask = Self.fetchInstantHttpImages(
                effectiveQuery: effectiveQuery,
                filter: filter,
                nsfwMode: nsfwMode
            )
            async let googleTask = headlessGoogleSearcher.searchGoogleImages(
                query: query,
                filter: filter,
                nsfwMode: nsfwMode
            )

            let fastResults = await instantTask
            let apiResults = await apiResultsTask
            let initialList = Self.mergeUnique(apiResults, fastResults)
            if !initialList.isEmpty {
                onProgressiveResults(initialList)
            }

            let googleResults = await googleTask
            let filteredGoogle: [ImageResult]
            if filter.animatedOnly {
                filteredGoogle = googleResults.filter {
                    !$0.fullImageUrl.contains("encrypted-tbn0.gstatic.com")
                }
            } else {
                filteredGoogle = googleResults
            }
            let combined = Self.interleaveResults(
                primary: filter.animatedOnly ? (apiResults + fastResults) : (apiResults + filteredGoogle),
                secondary: filter.animatedOnly ? filteredGoogle : fastResults
            )
            onProgressiveResults(combined)
            return combined
        }
    }

    public nonisolated static func fetchInstantHttpImages(
        effectiveQuery: String,
        filter: SearchFilter,
        nsfwMode: Bool
    ) async -> [ImageResult] {
        guard let encoded = effectiveQuery.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            return []
        }
        let qftFilter: String
        if filter.animatedOnly {
            qftFilter = "&qft=+filterui:photo-animatedgif"
        } else if filter.transparentOnly {
            qftFilter = "&qft=+filterui:photo-transparent"
        } else {
            qftFilter = ""
        }
        let adltParam = nsfwMode ? "off" : "moderate"
        let headers = nsfwMode ? ["Cookie": "SRCHHPGUSR=ADLT=OFF"] : [:]
        let urlStr = "https://www.bing.com/images/async?q=\(encoded)&first=0&count=35&adlt=\(adltParam)\(qftFilter)"

        guard let html = await httpGetString(urlStr, extraHeaders: headers) else {
            return []
        }
        return SearchParsers.parseBingAsyncImagesHtml(html, forceGifHint: filter.animatedOnly)
    }

    public nonisolated static func fetchOptionalApiResults(
        effectiveQuery: String,
        filter: SearchFilter,
        nsfwMode: Bool,
        apiKey: String
    ) async -> [ImageResult] {
        guard let encoded = effectiveQuery.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            return []
        }

        if apiKey.count >= 40 && apiKey.allSatisfy({ $0.isLetter || $0.isNumber }) {
            let tbs = filter.animatedOnly ? "&tbs=itp:animated" : ""
            let safe = nsfwMode ? "off" : "active"
            let serpUrl = "https://serpapi.com/search.json?engine=google_images&q=\(encoded)&safe=\(safe)\(tbs)&api_key=\(apiKey)"
            if let json = await httpGetString(serpUrl) {
                let parsed = SearchParsers.parseSerpApiImagesJson(json, forceGifHint: filter.animatedOnly)
                if !parsed.isEmpty { return parsed }
            }
        }

        let braveSafe = nsfwMode ? "off" : "moderate"
        let braveUrl = "https://api.search.brave.com/res/v1/images/search?q=\(encoded)&safesearch=\(braveSafe)&count=30"
        if let json = await httpGetString(braveUrl, extraHeaders: ["X-Subscription-Token": apiKey]) {
            return SearchParsers.parseBraveImagesJson(json, forceGifHint: filter.animatedOnly)
        }
        return []
    }

    private nonisolated static func httpGetString(
        _ urlStr: String,
        extraHeaders: [String: String] = [:]
    ) async -> String? {
        guard let url = URL(string: urlStr) else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.timeoutInterval = 4.5
        req.setValue(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            forHTTPHeaderField: "User-Agent"
        )
        req.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
        for (k, v) in extraHeaders {
            req.setValue(v, forHTTPHeaderField: k)
        }

        do {
            let (data, response) = try await URLSession.shared.data(for: req)
            if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                return nil
            }
            return String(data: data, encoding: .utf8)
        } catch {
            return nil
        }
    }

    public nonisolated static func mergeUnique(
        _ first: [ImageResult],
        _ second: [ImageResult]
    ) -> [ImageResult] {
        var seen = Set<String>()
        var out: [ImageResult] = []
        for item in first + second {
            if seen.insert(item.fullImageUrl).inserted {
                out.append(item)
            }
        }
        return out
    }

    public nonisolated static func interleaveResults(
        primary: [ImageResult],
        secondary: [ImageResult]
    ) -> [ImageResult] {
        var seen = Set<String>()
        var out: [ImageResult] = []
        let maxLen = max(primary.count, secondary.count)
        for i in 0..<maxLen {
            if i < primary.count {
                let p = primary[i]
                if seen.insert(p.fullImageUrl).inserted {
                    out.append(p)
                }
            }
            if i < secondary.count {
                let s = secondary[i]
                if seen.insert(s.fullImageUrl).inserted {
                    out.append(s)
                }
            }
        }
        return out
    }
}
