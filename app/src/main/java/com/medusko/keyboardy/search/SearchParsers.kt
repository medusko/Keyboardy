package com.medusko.keyboardy.search

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress

object SearchParsers {

    private val BING_M_ATTR_REGEX = Regex("""m="(\{[^"]+\})"""")
    private val TENOR_URL_ID_REGEX = Regex(
        """https://media[0-9]*\.tenor\.com/(?:m/)?([A-Za-z0-9_\-]{11})AAAA[A-Za-z]/([^\s"<>]+?\.gif)"""
    )
    private val GOOGLE_SCRIPT_PAIR_REGEX = Regex(
        """\["(https://encrypted-tbn0\.gstatic\.com/images\?q=tbn:[^"]+)",\d+,\d+\]\s*,\s*\["(https?://[^"]+)",\d+,\d+\]"""
    )
    private val GOOGLE_THUMB_ONLY_REGEX = Regex(
        """https://encrypted-tbn0\.gstatic\.com/images\?q=tbn:[A-Za-z0-9_\-:]+"""
    )

    /**
     * Converts any Tenor GIF URL into its fast ~100KB animated preview URL (`AAAAM`) and full HD URL (`AAAAC`).
     */
    fun normalizeTenorGifUrls(url: String): Pair<String, String>? {
        val match = TENOR_URL_ID_REGEX.find(url) ?: return null
        if (match.groupValues.size < 3) return null
        val tenorId = match.groupValues[1]
        val slug = match.groupValues[2]
        val thumb = "https://media.tenor.com/${tenorId}AAAAM/$slug"
        val full = "https://media1.tenor.com/m/${tenorId}AAAAC/$slug"
        return Pair(thumb, full)
    }

    /**
     * Parses the HTML fragment returned by https://www.bing.com/images/async?q=...
     * Each image card embeds a JSON object inside m="{...}" with:
     *  - murl: Direct full-resolution image or animated GIF URL
     *  - turl: Fast thumbnail URL
     *  - t: Title description
     */
    fun parseBingAsyncImagesHtml(html: String, forceGifHint: Boolean = false): List<ImageResult> {
        val results = mutableListOf<ImageResult>()
        val seenUrls = mutableSetOf<String>()

        for (match in BING_M_ATTR_REGEX.findAll(html)) {
            val escapedJson = match.groupValues[1]
            val unescaped = unescapeHtmlEntities(escapedJson)
            try {
                val obj = JSONObject(unescaped)
                val fullUrl = obj.optString("murl").trim()
                val thumbUrl = obj.optString("turl").trim()
                val title = obj.optString("t", "Image").trim()

                if (isSafeHttpUrl(fullUrl) && seenUrls.add(fullUrl)) {
                    val tenorPair = normalizeTenorGifUrls(fullUrl)
                    val effectiveThumb = when {
                        tenorPair != null -> tenorPair.first
                        thumbUrl.isNotEmpty() && isSafeHttpUrl(thumbUrl) -> thumbUrl
                            .replace("&amp;c=7", "&amp;c=0")
                            .replace("&c=7", "&c=0")
                        else -> fullUrl
                    }
                    val effectiveFull = tenorPair?.second ?: fullUrl
                    val isGif = forceGifHint ||
                        effectiveFull.lowercase().contains(".gif") ||
                        title.lowercase().contains("gif")

                    results.add(
                        ImageResult(
                            id = "inst_${results.size}_${effectiveFull.hashCode()}",
                            title = title,
                            thumbnailUrl = effectiveThumb,
                            fullImageUrl = effectiveFull,
                            isAnimatedGif = isGif,
                            sourceLabel = "⚡"
                        )
                    )
                }
            } catch (_: Exception) {
                // Ignore malformed item and continue
            }
        }
        return results
    }

    /**
     * Parses JSON array returned by HeadlessGoogleWebViewSearcher.evaluateJavascript().
     * Expected format: [{"thumb":"https://...","full":"https://...","title":"...","isGif":true}, ...]
     */
    fun parseGoogleWebViewJson(rawEvaluateJsResult: String, forceGifHint: Boolean = false): List<ImageResult> {
        val results = mutableListOf<ImageResult>()
        val seen = mutableSetOf<String>()
        try {
            val trimmed = rawEvaluateJsResult.trim()
            if (trimmed.isEmpty() || trimmed == "null") return emptyList()

            // WebView.evaluateJavascript wraps string results in quotes or returns JSON array directly
            val jsonArrayStr = if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                JSONObject("{\"v\":$trimmed}").getString("v")
            } else {
                trimmed
            }

            val arr = JSONArray(jsonArrayStr)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val fullUrl = item.optString("full").trim()
                val thumbUrl = item.optString("thumb").trim()
                val title = item.optString("title", "Google Image").trim()
                val primaryUrl = fullUrl.ifEmpty { thumbUrl }
                if (!isSafeImageUrl(primaryUrl) || !seen.add(primaryUrl)) continue

                val isGif = forceGifHint ||
                    item.optBoolean("isGif", false) ||
                    primaryUrl.lowercase().contains(".gif") ||
                    title.lowercase().contains("gif")

                results.add(
                    ImageResult(
                        id = "gweb_${i}_${primaryUrl.hashCode()}",
                        title = title,
                        thumbnailUrl = thumbUrl.takeIf { isSafeImageUrl(it) } ?: primaryUrl,
                        fullImageUrl = primaryUrl,
                        isAnimatedGif = isGif,
                        sourceLabel = "G"
                    )
                )
            }
        } catch (_: Exception) {
            // Return whatever was parsed so far
        }
        return results
    }

    /**
     * Parses raw Google Images HTML/script blocks if fetched directly.
     */
    fun parseGoogleImagesHtml(html: String, forceGifHint: Boolean = false): List<ImageResult> {
        val results = mutableListOf<ImageResult>()
        val seen = mutableSetOf<String>()

        for (match in GOOGLE_SCRIPT_PAIR_REGEX.findAll(html)) {
            val thumb = decodeUnicodeEscapes(match.groupValues[1])
            val full = decodeUnicodeEscapes(match.groupValues[2])
            if (isSafeImageUrl(full) && !full.contains("gstatic.com") && seen.add(full)) {
                val isGif = forceGifHint || full.lowercase().contains(".gif")
                results.add(
                    ImageResult(
                        id = "ghtml_${results.size}_${full.hashCode()}",
                        title = "Google Image",
                        thumbnailUrl = thumb.takeIf { isSafeImageUrl(it) } ?: full,
                        fullImageUrl = full,
                        isAnimatedGif = isGif,
                        sourceLabel = "G"
                    )
                )
            }
        }

        if (results.isEmpty()) {
            for (match in GOOGLE_THUMB_ONLY_REGEX.findAll(html)) {
                val thumb = decodeUnicodeEscapes(match.value)
                if (isSafeHttpUrl(thumb) && seen.add(thumb)) {
                    results.add(
                        ImageResult(
                            id = "gthumb_${results.size}_${thumb.hashCode()}",
                            title = "Google Image",
                            thumbnailUrl = thumb,
                            fullImageUrl = thumb,
                            isAnimatedGif = false,
                            sourceLabel = "G"
                        )
                    )
                }
            }
        }

        return results
    }

    /**
     * Parses optional SerpApi (engine=google_images) JSON response.
     */
    fun parseSerpApiImagesJson(jsonStr: String, forceGifHint: Boolean = false): List<ImageResult> {
        val results = mutableListOf<ImageResult>()
        try {
            val root = JSONObject(jsonStr)
            val arr = root.optJSONArray("images_results") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val full = obj.optString("original").trim()
                val thumb = obj.optString("thumbnail").trim()
                val title = obj.optString("title", "Google Image").trim()
                val primary = full.ifEmpty { thumb }
                if (!isSafeHttpUrl(primary)) continue
                val isGif = forceGifHint || primary.lowercase().contains(".gif")
                results.add(
                    ImageResult(
                        id = "serp_${i}_${primary.hashCode()}",
                        title = title,
                        thumbnailUrl = thumb.takeIf { isSafeHttpUrl(it) } ?: primary,
                        fullImageUrl = primary,
                        isAnimatedGif = isGif,
                        sourceLabel = "G"
                    )
                )
            }
        } catch (_: Exception) {
        }
        return results
    }

    /**
     * Parses optional Brave Search Images API JSON response.
     */
    fun parseBraveImagesJson(jsonStr: String, forceGifHint: Boolean = false): List<ImageResult> {
        val results = mutableListOf<ImageResult>()
        try {
            val root = JSONObject(jsonStr)
            val arr = root.optJSONArray("results") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val props = obj.optJSONObject("properties")
                val thumbObj = obj.optJSONObject("thumbnail")
                val full = props?.optString("url")?.trim().orEmpty()
                val thumb = thumbObj?.optString("src")?.trim().orEmpty()
                val title = obj.optString("title", "Brave Image").trim()
                val primary = full.ifEmpty { thumb }
                if (!isSafeHttpUrl(primary)) continue
                val isGif = forceGifHint || primary.lowercase().contains(".gif")
                results.add(
                    ImageResult(
                        id = "brave_${i}_${primary.hashCode()}",
                        title = title,
                        thumbnailUrl = thumb.takeIf { isSafeHttpUrl(it) } ?: primary,
                        fullImageUrl = primary,
                        isAnimatedGif = isGif,
                        sourceLabel = "B"
                    )
                )
            }
        } catch (_: Exception) {
        }
        return results
    }

    /**
     * Inspects magic bytes of downloaded image data to return the accurate (mimeType, fileExtension).
     */
    fun detectImageMimeTypeAndExtension(bytes: ByteArray): Pair<String, String> {
        if (bytes.size >= 6) {
            // GIF87a or GIF89a
            if (bytes[0] == 'G'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == '8'.code.toByte()
            ) {
                return "image/gif" to "gif"
            }
        }
        if (bytes.size >= 8) {
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            if (bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte()
            ) {
                return "image/png" to "png"
            }
        }
        if (bytes.size >= 12) {
            // RIFF....WEBP
            if (bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() &&
                bytes[11] == 'P'.code.toByte()
            ) {
                return "image/webp" to "webp"
            }
        }
        // Default to JPEG (FF D8 FF ...)
        return "image/jpeg" to "jpg"
    }

    fun unescapeHtmlEntities(input: String): String {
        return input
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun decodeUnicodeEscapes(input: String): String {
        return input
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
    }

    /**
     * Validates that a URL uses http/https, carries no embedded credentials, and does not target
     * loopback, link-local, CGNAT, multicast, or private/internal network addresses.
     */
    fun isSafeHttpUrl(urlStr: String): Boolean {
        val trimmed = urlStr.trim()
        if (trimmed.isEmpty()) return false
        val parsed = trimmed.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "http" && parsed.scheme != "https") return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        return !isBlockedHostname(parsed.host)
    }

    /**
     * Returns true if [rawHost] is localhost, an internal/reserved hostname, a single-label
     * hostname, or any literal IPv4/IPv6 address (including decimal/octal/hex/shorthand/mapped forms)
     * that is not a publicly routable address. Performs zero DNS network calls.
     */
    fun isBlockedHostname(rawHost: String): Boolean {
        val host = rawHost.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .trimEnd('.')
            .lowercase()
        if (host.isEmpty() || host.contains('%')) return true

        if (host == "localhost" ||
            host.endsWith(".localhost") ||
            host == "localhost.localdomain" ||
            host == "ip6-localhost" ||
            host == "ip6-loopback" ||
            host == "local" ||
            host.endsWith(".local") ||
            host.endsWith(".internal") ||
            host.endsWith(".arpa")
        ) {
            return true
        }

        val literal = parseIpLiteral(host)
        if (literal != null) {
            return !isPublicIpAddress(literal)
        }

        // Single-label hostnames without dots (e.g. http://router/ or http://nas/) are LAN-only.
        if (!host.contains('.')) return true

        return false
    }

    /**
     * Returns true only if [addr] is a globally routable public IPv4 or IPv6 address.
     */
    fun isPublicIpAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return false
        }

        val bytes = addr.address
        return when (bytes.size) {
            4 -> isPublicIpv4Bytes(bytes[0], bytes[1], bytes[2], bytes[3])
            16 -> isPublicIpv6Bytes(bytes)
            else -> false
        }
    }

    private fun isPublicIpv4Bytes(a0: Byte, a1: Byte, a2: Byte, @Suppress("UNUSED_PARAMETER") a3: Byte): Boolean {
        val b0 = a0.toInt() and 0xFF
        val b1 = a1.toInt() and 0xFF
        val b2 = a2.toInt() and 0xFF

        if (b0 == 0) return false // 0.0.0.0/8 ("this" network)
        if (b0 == 10) return false // 10.0.0.0/8 (RFC 1918)
        if (b0 == 100 && b1 in 64..127) return false // 100.64.0.0/10 (RFC 6598 CGNAT)
        if (b0 == 127) return false // 127.0.0.0/8 (loopback)
        if (b0 == 169 && b1 == 254) return false // 169.254.0.0/16 (link-local / metadata)
        if (b0 == 172 && b1 in 16..31) return false // 172.16.0.0/12 (RFC 1918)
        if (b0 == 192 && b1 == 0 && b2 == 0) return false // 192.0.0.0/24 (IETF protocol)
        if (b0 == 192 && b1 == 168) return false // 192.168.0.0/16 (RFC 1918)
        if (b0 == 198 && b1 in 18..19) return false // 198.18.0.0/15 (benchmarking)
        if (b0 >= 224) return false // 224.0.0.0/4 multicast + 240.0.0.0/4 reserved/broadcast
        return true
    }

    private fun isPublicIpv6Bytes(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF

        // ::/128 (unspecified) or ::1/128 (loopback)
        val first15Zero = (0..14).all { bytes[it].toInt() == 0 }
        if (first15Zero && (bytes[15].toInt() == 0 || bytes[15].toInt() == 1)) return false

        // fc00::/7 (Unique Local Addresses: fc00:: through fdff::)
        if ((b0 and 0xFE) == 0xFC) return false

        // fe80::/10 (link-local) and fec0::/10 (deprecated site-local)
        if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return false
        if (b0 == 0xFE && (b1 and 0xC0) == 0xC0) return false

        // ff00::/8 (multicast)
        if (b0 == 0xFF) return false

        // IPv4-mapped (::ffff:0:0/96) and IPv4-compatible (::/96)
        val first10Zero = (0..9).all { bytes[it].toInt() == 0 }
        if (first10Zero) {
            val b10 = bytes[10].toInt() and 0xFF
            val b11 = bytes[11].toInt() and 0xFF
            if ((b10 == 0xFF && b11 == 0xFF) || (b10 == 0 && b11 == 0)) {
                return isPublicIpv4Bytes(bytes[12], bytes[13], bytes[14], bytes[15])
            }
        }

        // IPv4-translated (::ffff:0:0:0/96)
        if ((0..7).all { bytes[it].toInt() == 0 } &&
            (bytes[8].toInt() and 0xFF) == 0xFF &&
            (bytes[9].toInt() and 0xFF) == 0xFF &&
            bytes[10].toInt() == 0 &&
            bytes[11].toInt() == 0
        ) {
            return isPublicIpv4Bytes(bytes[12], bytes[13], bytes[14], bytes[15])
        }

        // NAT64 (64:ff9b::/96 and local-use 64:ff9b:1::/48)
        if (b0 == 0x00 && b1 == 0x64 &&
            (bytes[2].toInt() and 0xFF) == 0xFF && (bytes[3].toInt() and 0xFF) == 0x9B
        ) {
            if ((4..11).all { bytes[it].toInt() == 0 }) {
                return isPublicIpv4Bytes(bytes[12], bytes[13], bytes[14], bytes[15])
            }
            if (bytes[4].toInt() == 0 && bytes[5].toInt() == 1) {
                return false
            }
        }

        // 6to4 (2002::/16) embeds IPv4 in bytes 2..5
        if (b0 == 0x20 && b1 == 0x02) {
            return isPublicIpv4Bytes(bytes[2], bytes[3], bytes[4], bytes[5])
        }

        return true
    }

    private fun parseIpLiteral(host: String): InetAddress? {
        parseIpv4Literal(host)?.let { ipv4Bytes ->
            return try {
                InetAddress.getByAddress(host, ipv4Bytes)
            } catch (_: Exception) {
                null
            }
        }
        // Only pass strict IPv6-literal syntax (contains ':' and only hex/:/.) to InetAddress.getByName
        // so it never triggers a DNS lookup.
        if (host.contains(':') && host.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' }) {
            return try {
                InetAddress.getByName(host)
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    /**
     * Parses decimal, octal, hex, and shorthand IPv4 literals (e.g. 2130706433, 0177.0.0.1,
     * 0x7f000001, 127.1) according to inet_aton semantics without performing DNS lookups.
     */
    private fun parseIpv4Literal(host: String): ByteArray? {
        if (host.isEmpty()) return null
        val parts = host.split('.')
        if (parts.isEmpty() || parts.size > 4 || parts.any { it.isEmpty() }) return null

        val nums = LongArray(parts.size)
        for (i in parts.indices) {
            val part = parts[i]
            val parsed = parseIpv4Part(part) ?: return null
            nums[i] = parsed
        }

        val value: Long = when (parts.size) {
            1 -> {
                if (nums[0] > 0xFFFFFFFFL) return null
                nums[0]
            }
            2 -> {
                if (nums[0] > 0xFFL || nums[1] > 0xFFFFFFL) return null
                (nums[0] shl 24) or nums[1]
            }
            3 -> {
                if (nums[0] > 0xFFL || nums[1] > 0xFFL || nums[2] > 0xFFFFL) return null
                (nums[0] shl 24) or (nums[1] shl 16) or nums[2]
            }
            4 -> {
                if (nums.any { it > 0xFFL }) return null
                (nums[0] shl 24) or (nums[1] shl 16) or (nums[2] shl 8) or nums[3]
            }
            else -> return null
        }

        return byteArrayOf(
            ((value ushr 24) and 0xFFL).toByte(),
            ((value ushr 16) and 0xFFL).toByte(),
            ((value ushr 8) and 0xFFL).toByte(),
            (value and 0xFFL).toByte()
        )
    }

    private fun parseIpv4Part(part: String): Long? {
        if (part.isEmpty()) return null
        return try {
            when {
                part.startsWith("0x") || part.startsWith("0X") -> {
                    val hex = part.substring(2)
                    if (hex.isEmpty() || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                    hex.toLong(16)
                }
                part.length > 1 && part.startsWith("0") -> {
                    if (!part.all { it in '0'..'7' }) return null
                    part.toLong(8)
                }
                else -> {
                    if (!part.all { it in '0'..'9' }) return null
                    part.toLong(10)
                }
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * Checks if a URL is safe and points to a legitimate image, filtering out SVG logos,
     * Google search links, and non-image Google assets.
     */
    fun isSafeImageUrl(urlStr: String): Boolean {
        if (!isSafeHttpUrl(urlStr)) return false
        val lower = urlStr.lowercase()
        if (lower.contains(".svg") ||
            lower.contains("fonts.gstatic.com") ||
            lower.contains("productlogos") ||
            lower.contains("google.com/images/branding") ||
            lower.contains("google.com/search") ||
            lower.contains("gstatic.com/kp")
        ) {
            return false
        }
        if (lower.contains("google.") && !lower.contains("encrypted-tbn0.gstatic.com")) {
            return false
        }
        return true
    }

    /**
     * Reads at most [maxBytes] from [input], returning null if the stream exceeds [maxBytes].
     */
    fun readBoundedBytes(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
