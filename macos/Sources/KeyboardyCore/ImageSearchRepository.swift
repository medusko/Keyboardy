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
        let animatedRequested = filter.requiresAnimatedGif(for: query)

        async let apiResultsTask: [ImageResult] = optionalApiKey.isEmpty
            ? []
            : Self.fetchOptionalApiResults(
                effectiveQuery: effectiveQuery,
                filter: filter,
                animatedRequested: animatedRequested,
                nsfwMode: nsfwMode,
                apiKey: optionalApiKey
            )

        // When GIFs or Reactions are requested (or "gif" is in the query), fetch Tenor GIFs + WebView GIFs in parallel!
        if animatedRequested {
            async let tenorTask = Self.fetchTenorGifImages(query: query)
            async let webViewGifTask = headlessGoogleSearcher.searchGoogleImages(
                query: query,
                filter: filter,
                nsfwMode: nsfwMode,
                preferBingWebView: true
            )

            let tenorGifs = await tenorTask
            let apiItems = await apiResultsTask
            let initialGifs = Self.mergeUnique(apiItems, tenorGifs)
            if !initialGifs.isEmpty {
                onProgressiveResults(initialGifs)
            }

            let webViewItems = await webViewGifTask.filter {
                $0.fullImageUrl.lowercased().contains(".gif")
            }
            let combinedGifs = Self.interleaveResults(
                primary: initialGifs,
                secondary: webViewItems
            )
            let finalGifs = combinedGifs.isEmpty ? initialGifs : combinedGifs
            onProgressiveResults(finalGifs)
            return finalGifs
        }

        switch engineMode {
        case .instantHttp:
            async let fastTask = Self.fetchInstantHttpImages(
                rawQuery: query,
                effectiveQuery: effectiveQuery,
                filter: filter,
                animatedRequested: false,
                nsfwMode: nsfwMode
            )
            async let tenorTask: [ImageResult] = (filter == .all) ? Self.fetchTenorGifImages(query: query) : []
            let fast = await fastTask
            let tenor = await tenorTask
            let apiItems = await apiResultsTask
            var merged = Self.interleaveEveryN(primary: Self.mergeUnique(apiItems, fast), secondary: tenor, every: 4)
            if merged.count < 8 {
                let webFallback = await headlessGoogleSearcher.searchGoogleImages(
                    query: query,
                    filter: filter,
                    nsfwMode: nsfwMode,
                    preferBingWebView: true
                )
                merged = Self.interleaveEveryN(primary: Self.mergeUnique(apiItems, webFallback), secondary: tenor, every: 4)
            }
            onProgressiveResults(merged)
            return merged

        case .googleWebView:
            async let googleTask = headlessGoogleSearcher.searchGoogleImages(
                query: query,
                filter: filter,
                nsfwMode: nsfwMode,
                preferBingWebView: false
            )
            async let tenorTask: [ImageResult] = (filter == .all) ? Self.fetchTenorGifImages(query: query) : []
            let googleItems = await googleTask
            let tenorItems = await tenorTask
            let finalItems = Self.interleaveEveryN(primary: googleItems, secondary: tenorItems, every: 4)
            onProgressiveResults(finalItems)
            return finalItems

        case .hybrid:
            async let instantTask = Self.fetchInstantHttpImages(
                rawQuery: query,
                effectiveQuery: effectiveQuery,
                filter: filter,
                animatedRequested: false,
                nsfwMode: nsfwMode
            )
            async let tenorTask: [ImageResult] = (filter == .all) ? Self.fetchTenorGifImages(query: query) : []
            async let webViewTask = headlessGoogleSearcher.searchGoogleImages(
                query: query,
                filter: filter,
                nsfwMode: nsfwMode,
                preferBingWebView: true
            )

            let fastResults = await instantTask
            let apiResults = await apiResultsTask
            if !fastResults.isEmpty {
                let initialList = Self.mergeUnique(apiResults, fastResults)
                onProgressiveResults(initialList)
            }

            let webViewResults = await webViewTask
            let tenorGifs = await tenorTask
            let allPhotos = Self.mergeUnique(apiResults + webViewResults, fastResults)
            let combined = Self.interleaveEveryN(
                primary: allPhotos,
                secondary: tenorGifs,
                every: 4
            )
            onProgressiveResults(combined)
            return combined
        }
    }

    /// Fetches 25–50 genuine animated GIFs from Tenor (`https://tenor.com/search/<slug>-gifs`).
    /// Each result uses Tenor's ~100KB `AAAAM` animated GIF for `thumbnailUrl` so it animates in a loop within ~60ms!
    public nonisolated static func fetchTenorGifImages(query: String) async -> [ImageResult] {
        let stripped = query
            .replacingOccurrences(of: #"\bgifs?\b"#, with: "", options: [.regularExpression, .caseInsensitive])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let baseQuery = stripped.isEmpty ? query.trimmingCharacters(in: .whitespacesAndNewlines) : stripped
        guard !baseQuery.isEmpty else { return [] }

        let hyphenated = baseQuery
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: "-")
        guard let encodedSlug = hyphenated.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) else {
            return []
        }

        let urlStr = "https://tenor.com/search/\(encodedSlug)-gifs"
        guard let html = await httpGetString(urlStr) else {
            return []
        }
        return SearchParsers.parseTenorSearchHtml(html)
    }

    public nonisolated static func fetchInstantHttpImages(
        rawQuery: String = "",
        effectiveQuery: String,
        filter: SearchFilter,
        animatedRequested: Bool? = nil,
        nsfwMode: Bool
    ) async -> [ImageResult] {
        guard let encoded = effectiveQuery.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            return []
        }
        let wantAnimated = animatedRequested ?? filter.requiresAnimatedGif(for: effectiveQuery)
        let qftFilter: String
        if wantAnimated {
            qftFilter = "&qft=+filterui:photo-animatedgif"
        } else if filter.transparentOnly {
            qftFilter = "&qft=+filterui:photo-transparent"
        } else {
            qftFilter = ""
        }
        // Note: Passing adlt=off on raw HTTP /images/async triggers Bing anti-bot poisoning for many queries,
        // so we use moderate on the raw HTTP fast path and let the real WKWebView handle full unfiltered results.
        let urlStr = "https://www.bing.com/images/async?q=\(encoded)&first=0&count=35&adlt=moderate\(qftFilter)"

        guard let html = await httpGetString(urlStr) else {
            return []
        }
        let parsed = SearchParsers.parseBingAsyncImagesHtml(html, forceGifHint: wantAnimated)

        // Guard against Bing /images/async anti-scraper poisoning (where Bing returns 1 item or unrelated stock items):
        let checkQuery = rawQuery.isEmpty ? effectiveQuery : rawQuery
        if !isRelevantResultBatch(parsed, forQuery: checkQuery) {
            return []
        }
        return parsed
    }

    /// Verifies that a raw HTTP result batch actually matches the user's query (rejecting Bing's poisoned responses).
    private nonisolated static func isRelevantResultBatch(_ items: [ImageResult], forQuery query: String) -> Bool {
        guard items.count >= 6 else { return false }
        let queryTokens = query.lowercased()
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { $0.count >= 2 && $0 != "gif" && $0 != "gifs" && $0 != "meme" }
        guard !queryTokens.isEmpty else { return true }

        let topSlice = items.prefix(8)
        var matchingItems = 0
        for item in topSlice {
            let haystack = (item.title + " " + item.fullImageUrl).lowercased()
            if queryTokens.contains(where: { haystack.contains($0) }) {
                matchingItems += 1
            }
        }
        return matchingItems >= 2
    }

    public nonisolated static func fetchOptionalApiResults(
        effectiveQuery: String,
        filter: SearchFilter,
        animatedRequested: Bool? = nil,
        nsfwMode: Bool,
        apiKey: String
    ) async -> [ImageResult] {
        guard let encoded = effectiveQuery.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            return []
        }
        let wantAnimated = animatedRequested ?? filter.requiresAnimatedGif(for: effectiveQuery)

        if apiKey.count >= 40 && apiKey.allSatisfy({ $0.isLetter || $0.isNumber }) {
            let tbs = wantAnimated ? "&tbs=itp:animated" : ""
            let safe = nsfwMode ? "off" : "active"
            let serpUrl = "https://serpapi.com/search.json?engine=google_images&q=\(encoded)&safe=\(safe)\(tbs)&api_key=\(apiKey)"
            if let json = await httpGetString(serpUrl) {
                let parsed = SearchParsers.parseSerpApiImagesJson(json, forceGifHint: wantAnimated)
                if !parsed.isEmpty { return parsed }
            }
        }

        let braveSafe = nsfwMode ? "off" : "moderate"
        let braveUrl = "https://api.search.brave.com/res/v1/images/search?q=\(encoded)&safesearch=\(braveSafe)&count=30"
        if let json = await httpGetString(braveUrl, extraHeaders: ["X-Subscription-Token": apiKey]) {
            return SearchParsers.parseBraveImagesJson(json, forceGifHint: wantAnimated)
        }
        return []
    }

    private static let maxTextResponseBytes = 5 * 1024 * 1024 // 5 MB

    private nonisolated static func httpGetString(
        _ urlStr: String,
        extraHeaders: [String: String] = [:]
    ) async -> String? {
        guard SearchParsers.isSafeHttpUrl(urlStr),
              let url = URL(string: urlStr) else { return nil }
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

        guard let data = await SearchParsers.fetchBoundedData(for: req, maxBytes: maxTextResponseBytes) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
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

    /// Interleaves 1 item from `secondary` (e.g. animated GIFs) after every `every` items from `primary` (photos).
    public nonisolated static func interleaveEveryN(
        primary: [ImageResult],
        secondary: [ImageResult],
        every: Int
    ) -> [ImageResult] {
        if primary.isEmpty { return secondary }
        if secondary.isEmpty { return primary }
        var seen = Set<String>()
        var out: [ImageResult] = []
        var secIdx = 0
        let step = max(every, 1)

        for (i, p) in primary.enumerated() {
            if seen.insert(p.fullImageUrl).inserted {
                out.append(p)
            }
            if (i + 1) % step == 0, secIdx < secondary.count {
                let s = secondary[secIdx]
                secIdx += 1
                if seen.insert(s.fullImageUrl).inserted {
                    out.append(s)
                }
            }
        }
        return out
    }
}
