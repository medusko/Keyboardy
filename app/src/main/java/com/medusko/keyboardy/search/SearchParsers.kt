package com.medusko.keyboardy.search

import org.json.JSONArray
import org.json.JSONObject

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

                if (fullUrl.startsWith("http") && seenUrls.add(fullUrl)) {
                    val tenorPair = normalizeTenorGifUrls(fullUrl)
                    val effectiveThumb = when {
                        tenorPair != null -> tenorPair.first
                        thumbUrl.isNotEmpty() -> thumbUrl
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
                if (!primaryUrl.startsWith("http") || !seen.add(primaryUrl)) continue

                val isGif = forceGifHint ||
                    item.optBoolean("isGif", false) ||
                    primaryUrl.lowercase().contains(".gif") ||
                    title.lowercase().contains("gif")

                results.add(
                    ImageResult(
                        id = "gweb_${i}_${primaryUrl.hashCode()}",
                        title = title,
                        thumbnailUrl = thumbUrl.ifEmpty { primaryUrl },
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
            if (full.startsWith("http") && !full.contains("gstatic.com") && seen.add(full)) {
                val isGif = forceGifHint || full.lowercase().contains(".gif")
                results.add(
                    ImageResult(
                        id = "ghtml_${results.size}_${full.hashCode()}",
                        title = "Google Image",
                        thumbnailUrl = thumb,
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
                if (seen.add(thumb)) {
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
                if (!primary.startsWith("http")) continue
                val isGif = forceGifHint || primary.lowercase().contains(".gif")
                results.add(
                    ImageResult(
                        id = "serp_${i}_${primary.hashCode()}",
                        title = title,
                        thumbnailUrl = thumb.ifEmpty { primary },
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
                if (!primary.startsWith("http")) continue
                val isGif = forceGifHint || primary.lowercase().contains(".gif")
                results.add(
                    ImageResult(
                        id = "brave_${i}_${primary.hashCode()}",
                        title = title,
                        thumbnailUrl = thumb.ifEmpty { primary },
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
}
