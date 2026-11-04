import Foundation

public enum SearchParsers {

    private static let bingMAttrRegex: NSRegularExpression = {
        try! NSRegularExpression(pattern: #"m="(\{[^"]+\})""#, options: [])
    }()

    private static let googleScriptPairRegex: NSRegularExpression = {
        try! NSRegularExpression(
            pattern: #"\["(https://encrypted-tbn0\.gstatic\.com/images\?q=tbn:[^"]+)",\d+,\d+\]\s*,\s*\["(https?://[^"]+)",\d+,\d+\]"#,
            options: []
        )
    }()

    private static let googleThumbOnlyRegex: NSRegularExpression = {
        try! NSRegularExpression(
            pattern: #"https://encrypted-tbn0\.gstatic\.com/images\?q=tbn:[A-Za-z0-9_\-:]+"#,
            options: []
        )
    }()

    private static let tenorImgTagRegex: NSRegularExpression = {
        try! NSRegularExpression(
            pattern: #"<img[^>]+src="(https://media[0-9]*\.tenor\.com/[^"]+\.gif)"[^>]*alt="([^"]*)""#,
            options: [.caseInsensitive]
        )
    }()

    private static let tenorUrlIdRegex: NSRegularExpression = {
        try! NSRegularExpression(
            pattern: #"https://media[0-9]*\.tenor\.com/(?:m/)?([A-Za-z0-9_\-]{11})AAAA[A-Za-z]/([^\s"<>]+?\.gif)"#,
            options: []
        )
    }()

    /// Converts any Tenor GIF URL into its fast ~100KB animated preview URL (`AAAAM`) and full HD URL (`AAAAC`).
    public static func normalizeTenorGifUrls(_ url: String) -> (animatedThumb: String, fullGif: String)? {
        let ns = url as NSString
        let range = NSRange(location: 0, length: ns.length)
        guard let m = tenorUrlIdRegex.firstMatch(in: url, options: [], range: range),
              m.numberOfRanges >= 3 else {
            return nil
        }
        let tenorId = ns.substring(with: m.range(at: 1))
        let slug = ns.substring(with: m.range(at: 2))
        let thumb = "https://media.tenor.com/\(tenorId)AAAAM/\(slug)"
        let full = "https://media1.tenor.com/m/\(tenorId)AAAAC/\(slug)"
        return (thumb, full)
    }

    /// Parses Tenor search HTML (`https://tenor.com/search/<slug>-gifs`) into fast-loading animated GIF results.
    /// Every returned item has `thumbnailUrl` set to Tenor's ~100KB animated `AAAAM` GIF so it animates in ~60ms!
    public static func parseTenorSearchHtml(_ html: String) -> [ImageResult] {
        var results: [ImageResult] = []
        var seenTenorIds = Set<String>()
        let nsHtml = html as NSString
        let fullRange = NSRange(location: 0, length: nsHtml.length)

        // 1. First match <img> tags with alt text
        let imgMatches = tenorImgTagRegex.matches(in: html, options: [], range: fullRange)
        for match in imgMatches {
            guard match.numberOfRanges >= 3 else { continue }
            let rawUrl = nsHtml.substring(with: match.range(at: 1))
            if rawUrl.contains("/assets/") || rawUrl.contains("tenor-logo") { continue }
            let rawAlt = unescapeHtmlEntities(nsHtml.substring(with: match.range(at: 2)))
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let title = rawAlt.isEmpty ? "Tenor GIF" : rawAlt

            if let pair = normalizeTenorGifUrls(rawUrl) {
                if seenTenorIds.insert(pair.animatedThumb).inserted {
                    results.append(
                        ImageResult(
                            id: "tenor_\(results.count)_\(pair.animatedThumb.hashValue)",
                            title: title,
                            thumbnailUrl: pair.animatedThumb,
                            fullImageUrl: pair.fullGif,
                            isAnimatedGif: true,
                            sourceLabel: "GIF"
                        )
                    )
                }
            } else if rawUrl.hasPrefix("http"), seenTenorIds.insert(rawUrl).inserted {
                results.append(
                    ImageResult(
                        id: "tenor_\(results.count)_\(rawUrl.hashValue)",
                        title: title,
                        thumbnailUrl: rawUrl,
                        fullImageUrl: rawUrl,
                        isAnimatedGif: true,
                        sourceLabel: "GIF"
                    )
                )
            }
        }

        // 2. Also scan any remaining Tenor GIF URLs in script/preload blocks
        let urlMatches = tenorUrlIdRegex.matches(in: html, options: [], range: fullRange)
        for match in urlMatches {
            guard match.numberOfRanges >= 3 else { continue }
            let tenorId = nsHtml.substring(with: match.range(at: 1))
            let slug = nsHtml.substring(with: match.range(at: 2))
            let thumb = "https://media.tenor.com/\(tenorId)AAAAM/\(slug)"
            let full = "https://media1.tenor.com/m/\(tenorId)AAAAC/\(slug)"
            if seenTenorIds.insert(thumb).inserted {
                let readableSlug = slug
                    .replacingOccurrences(of: ".gif", with: "")
                    .replacingOccurrences(of: "-", with: " ")
                results.append(
                    ImageResult(
                        id: "tenor_\(results.count)_\(thumb.hashValue)",
                        title: readableSlug.isEmpty ? "Tenor GIF" : readableSlug,
                        thumbnailUrl: thumb,
                        fullImageUrl: full,
                        isAnimatedGif: true,
                        sourceLabel: "GIF"
                    )
                )
            }
        }

        return results
    }

    /// Parses the HTML fragment returned by https://www.bing.com/images/async?q=...
    /// Each image card embeds a JSON object inside m="{...}" with:
    ///  - murl: Direct full-resolution image or animated GIF URL
    ///  - turl: Fast thumbnail URL
    ///  - t: Title description
    public static func parseBingAsyncImagesHtml(_ html: String, forceGifHint: Bool = false) -> [ImageResult] {
        var results: [ImageResult] = []
        var seenUrls = Set<String>()
        let nsHtml = html as NSString
        let matches = bingMAttrRegex.matches(in: html, options: [], range: NSRange(location: 0, length: nsHtml.length))

        for match in matches {
            guard match.numberOfRanges >= 2 else { continue }
            let escapedJson = nsHtml.substring(with: match.range(at: 1))
            let unescaped = unescapeHtmlEntities(escapedJson)
            guard let data = unescaped.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                continue
            }

            let fullUrl = (obj["murl"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let thumbUrl = (obj["turl"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let rawTitle = (obj["t"] as? String ?? "Image").trimmingCharacters(in: .whitespacesAndNewlines)
            let title = rawTitle.isEmpty ? "Image" : rawTitle

            if fullUrl.hasPrefix("http"), seenUrls.insert(fullUrl).inserted {
                let lowerFull = fullUrl.lowercased()
                let isKnownStaticExt = lowerFull.hasSuffix(".jpg") ||
                    lowerFull.hasSuffix(".jpeg") ||
                    lowerFull.hasSuffix(".png") ||
                    lowerFull.contains(".jpg?") ||
                    lowerFull.contains(".jpeg?") ||
                    lowerFull.contains(".png?")

                let isGif = !isKnownStaticExt && (
                    lowerFull.contains(".gif") ||
                    (forceGifHint && !lowerFull.contains("pexels.com"))
                )

                // If this Bing result is a Tenor GIF, use Tenor's fast ~100KB animated AAAAM thumbnail so it animates immediately!
                let effectiveThumb: String
                if let tenorPair = normalizeTenorGifUrls(fullUrl) {
                    effectiveThumb = tenorPair.animatedThumb
                } else {
                    effectiveThumb = thumbUrl.isEmpty ? fullUrl : thumbUrl
                }

                results.append(
                    ImageResult(
                        id: "inst_\(results.count)_\(fullUrl.hashValue)",
                        title: title,
                        thumbnailUrl: effectiveThumb,
                        fullImageUrl: fullUrl,
                        isAnimatedGif: isGif,
                        sourceLabel: "⚡"
                    )
                )
            }
        }
        return results
    }

    /// Parses JSON array returned by HeadlessGoogleWebViewSearcher.evaluateJavaScript().
    /// Expected format: [{"thumb":"https://...","full":"https://...","title":"...","isGif":true}, ...]
    public static func parseGoogleWebViewJson(_ rawEvaluateJsResult: String, forceGifHint: Bool = false) -> [ImageResult] {
        var results: [ImageResult] = []
        var seen = Set<String>()
        let trimmed = rawEvaluateJsResult.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == "null" { return [] }

        let jsonArrayStr: String
        if trimmed.hasPrefix("\"") && trimmed.hasSuffix("\"") {
            let wrapper = "{\"v\":\(trimmed)}"
            if let data = wrapper.data(using: .utf8),
               let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let inner = obj["v"] as? String {
                jsonArrayStr = inner
            } else {
                jsonArrayStr = trimmed
            }
        } else {
            jsonArrayStr = trimmed
        }

        guard let arrData = jsonArrayStr.data(using: .utf8),
              let arr = try? JSONSerialization.jsonObject(with: arrData) as? [[String: Any]] else {
            return []
        }

        for (i, item) in arr.enumerated() {
            let fullUrl = (item["full"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let thumbUrl = (item["thumb"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let rawTitle = (item["title"] as? String ?? "Google Image").trimmingCharacters(in: .whitespacesAndNewlines)
            let title = rawTitle.isEmpty ? "Google Image" : rawTitle
            let primaryUrl = fullUrl.isEmpty ? thumbUrl : fullUrl

            guard primaryUrl.hasPrefix("http"), seen.insert(primaryUrl).inserted else { continue }

            let itemIsGif = (item["isGif"] as? Bool) ?? false
            let lowerPrimary = primaryUrl.lowercased()
            let isKnownStaticExt = lowerPrimary.hasSuffix(".jpg") ||
                lowerPrimary.hasSuffix(".jpeg") ||
                lowerPrimary.hasSuffix(".png") ||
                lowerPrimary.contains(".jpg?") ||
                lowerPrimary.contains(".jpeg?") ||
                lowerPrimary.contains(".png?")
            let isGif = !isKnownStaticExt && (
                itemIsGif ||
                lowerPrimary.contains(".gif") ||
                forceGifHint
            )

            let effectiveThumb: String
            if let tenorPair = normalizeTenorGifUrls(primaryUrl) {
                effectiveThumb = tenorPair.animatedThumb
            } else {
                effectiveThumb = thumbUrl.isEmpty ? primaryUrl : thumbUrl
            }
            let srcLabel = (item["source"] as? String) ?? "G"

            results.append(
                ImageResult(
                    id: "gweb_\(i)_\(primaryUrl.hashValue)",
                    title: title,
                    thumbnailUrl: effectiveThumb,
                    fullImageUrl: primaryUrl,
                    isAnimatedGif: isGif,
                    sourceLabel: srcLabel
                )
            )
        }
        return results
    }

    /// Parses raw Google Images HTML/script blocks if fetched directly.
    public static func parseGoogleImagesHtml(_ html: String, forceGifHint: Bool = false) -> [ImageResult] {
        var results: [ImageResult] = []
        var seen = Set<String>()
        let nsHtml = html as NSString
        let fullRange = NSRange(location: 0, length: nsHtml.length)

        let pairMatches = googleScriptPairRegex.matches(in: html, options: [], range: fullRange)
        for match in pairMatches {
            guard match.numberOfRanges >= 3 else { continue }
            let thumb = decodeUnicodeEscapes(nsHtml.substring(with: match.range(at: 1)))
            let full = decodeUnicodeEscapes(nsHtml.substring(with: match.range(at: 2)))
            if full.hasPrefix("http"), !full.contains("gstatic.com"), seen.insert(full).inserted {
                let isGif = forceGifHint || full.lowercased().contains(".gif")
                results.append(
                    ImageResult(
                        id: "ghtml_\(results.count)_\(full.hashValue)",
                        title: "Google Image",
                        thumbnailUrl: thumb,
                        fullImageUrl: full,
                        isAnimatedGif: isGif,
                        sourceLabel: "G"
                    )
                )
            }
        }

        if results.isEmpty {
            let thumbMatches = googleThumbOnlyRegex.matches(in: html, options: [], range: fullRange)
            for match in thumbMatches {
                let thumb = decodeUnicodeEscapes(nsHtml.substring(with: match.range))
                if seen.insert(thumb).inserted {
                    results.append(
                        ImageResult(
                            id: "gthumb_\(results.count)_\(thumb.hashValue)",
                            title: "Google Image",
                            thumbnailUrl: thumb,
                            fullImageUrl: thumb,
                            isAnimatedGif: false,
                            sourceLabel: "G"
                        )
                    )
                }
            }
        }
        return results
    }

    /// Parses optional SerpApi (engine=google_images) JSON response.
    public static func parseSerpApiImagesJson(_ jsonStr: String, forceGifHint: Bool = false) -> [ImageResult] {
        guard let data = jsonStr.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let arr = root["images_results"] as? [[String: Any]] else {
            return []
        }

        var results: [ImageResult] = []
        for (i, obj) in arr.enumerated() {
            let full = (obj["original"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let thumb = (obj["thumbnail"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let rawTitle = (obj["title"] as? String ?? "Google Image").trimmingCharacters(in: .whitespacesAndNewlines)
            let title = rawTitle.isEmpty ? "Google Image" : rawTitle
            let primary = full.isEmpty ? thumb : full
            guard primary.hasPrefix("http") else { continue }
            let isGif = forceGifHint || primary.lowercased().contains(".gif")
            results.append(
                ImageResult(
                    id: "serp_\(i)_\(primary.hashValue)",
                    title: title,
                    thumbnailUrl: thumb.isEmpty ? primary : thumb,
                    fullImageUrl: primary,
                    isAnimatedGif: isGif,
                    sourceLabel: "G"
                )
            )
        }
        return results
    }

    /// Parses optional Brave Search Images API JSON response.
    public static func parseBraveImagesJson(_ jsonStr: String, forceGifHint: Bool = false) -> [ImageResult] {
        guard let data = jsonStr.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let arr = root["results"] as? [[String: Any]] else {
            return []
        }

        var results: [ImageResult] = []
        for (i, obj) in arr.enumerated() {
            let props = obj["properties"] as? [String: Any]
            let thumbObj = obj["thumbnail"] as? [String: Any]
            let full = (props?["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let thumb = (thumbObj?["src"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let rawTitle = (obj["title"] as? String ?? "Brave Image").trimmingCharacters(in: .whitespacesAndNewlines)
            let title = rawTitle.isEmpty ? "Brave Image" : rawTitle
            let primary = full.isEmpty ? thumb : full
            guard primary.hasPrefix("http") else { continue }
            let isGif = forceGifHint || primary.lowercased().contains(".gif")
            results.append(
                ImageResult(
                    id: "brave_\(i)_\(primary.hashValue)",
                    title: title,
                    thumbnailUrl: thumb.isEmpty ? primary : thumb,
                    fullImageUrl: primary,
                    isAnimatedGif: isGif,
                    sourceLabel: "B"
                )
            )
        }
        return results
    }

    /// Inspects magic bytes of downloaded image data to return the accurate (mimeType, fileExtension).
    public static func detectImageMimeTypeAndExtension(_ data: Data) -> (mimeType: String, ext: String) {
        let bytes = [UInt8](data.prefix(16))
        if bytes.count >= 6 {
            // GIF87a or GIF89a
            if bytes[0] == UInt8(ascii: "G") &&
               bytes[1] == UInt8(ascii: "I") &&
               bytes[2] == UInt8(ascii: "F") &&
               bytes[3] == UInt8(ascii: "8") {
                return ("image/gif", "gif")
            }
        }
        if bytes.count >= 8 {
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            if bytes[0] == 0x89 &&
               bytes[1] == 0x50 &&
               bytes[2] == 0x4E &&
               bytes[3] == 0x47 {
                return ("image/png", "png")
            }
        }
        if bytes.count >= 12 {
            // RIFF....WEBP
            if bytes[0] == UInt8(ascii: "R") &&
               bytes[1] == UInt8(ascii: "I") &&
               bytes[2] == UInt8(ascii: "F") &&
               bytes[3] == UInt8(ascii: "F") &&
               bytes[8] == UInt8(ascii: "W") &&
               bytes[9] == UInt8(ascii: "E") &&
               bytes[10] == UInt8(ascii: "B") &&
               bytes[11] == UInt8(ascii: "P") {
                return ("image/webp", "webp")
            }
        }
        return ("image/jpeg", "jpg")
    }

    public static func unescapeHtmlEntities(_ input: String) -> String {
        return input
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
    }

    public static func decodeUnicodeEscapes(_ input: String) -> String {
        return input
            .replacingOccurrences(of: "\\u003d", with: "=")
            .replacingOccurrences(of: "\\u0026", with: "&")
            .replacingOccurrences(of: "\\/", with: "/")
    }
}
