package com.medusko.keyboardy.search

import com.medusko.keyboardy.prefs.SearchEngineMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class ImageSearchRepository(
    private val headlessGoogleSearcher: HeadlessGoogleWebViewSearcher
) {

    /**
     * Searches for images/GIFs and calls [onProgressiveResults] as soon as fast results arrive,
     * then updates with merged Headless Google WebView results if running in HYBRID mode.
     */
    suspend fun searchImages(
        query: String,
        filter: SearchFilter,
        engineMode: SearchEngineMode,
        nsfwMode: Boolean,
        optionalApiKey: String,
        onProgressiveResults: (List<ImageResult>) -> Unit
    ): List<ImageResult> = coroutineScope {
        val effectiveQuery = filter.buildEffectiveQuery(query)
        if (effectiveQuery.isEmpty()) return@coroutineScope emptyList()

        // 1. If user supplied an optional SerpApi or Brave Search API key, try it first in parallel
        val apiDeferred = if (optionalApiKey.isNotBlank()) {
            async(Dispatchers.IO) {
                fetchOptionalApiResults(effectiveQuery, filter, nsfwMode, optionalApiKey)
            }
        } else {
            null
        }

        // 2. When GIFs or Reactions are requested, also fetch fast looping Tenor GIFs in parallel
        val tenorDeferred = if (filter.animatedOnly) {
            async(Dispatchers.IO) {
                fetchTenorGifImages(query)
            }
        } else {
            null
        }

        fun refineForFilter(items: List<ImageResult>): List<ImageResult> =
            SearchParsers.filterAndPrioritizeByType(
                items = items,
                forceGifHint = filter.animatedOnly,
                forceTransparentHint = filter.transparentOnly
            )

        when (engineMode) {
            SearchEngineMode.INSTANT_HTTP -> {
                val fast = fetchInstantHttpImages(effectiveQuery, filter, nsfwMode)
                val tenorItems = tenorDeferred?.await().orEmpty()
                val apiItems = apiDeferred?.await().orEmpty()
                val merged = refineForFilter(mergeUnique(apiItems + tenorItems, fast))
                onProgressiveResults(merged)
                merged
            }

            SearchEngineMode.GOOGLE_WEBVIEW -> {
                val googleItems = headlessGoogleSearcher.searchGoogleImages(query, filter, nsfwMode)
                val tenorItems = tenorDeferred?.await().orEmpty()
                val apiItems = apiDeferred?.await().orEmpty()
                val primary = mergeUnique(apiItems + tenorItems, googleItems)
                val finalItems = refineForFilter(
                    if (primary.isNotEmpty()) {
                        primary
                    } else {
                        fetchInstantHttpImages(effectiveQuery, filter, nsfwMode)
                    }
                )
                onProgressiveResults(finalItems)
                finalItems
            }

            SearchEngineMode.HYBRID -> {
                val instantDeferred = async(Dispatchers.IO) {
                    fetchInstantHttpImages(effectiveQuery, filter, nsfwMode)
                }
                val googleDeferred = async {
                    headlessGoogleSearcher.searchGoogleImages(query, filter, nsfwMode)
                }

                // Emit instant results (~150ms) right away so the grid populates immediately!
                val fastResults = instantDeferred.await()
                val tenorResults = tenorDeferred?.await().orEmpty()
                val apiResults = apiDeferred?.await().orEmpty()
                val initialList = refineForFilter(mergeUnique(apiResults + tenorResults, fastResults))
                if (initialList.isNotEmpty()) {
                    onProgressiveResults(initialList)
                }

                // Await Google Headless WebView results and interleave/merge them
                val googleResults = googleDeferred.await()
                val combined = refineForFilter(
                    interleaveResults(
                        primary = apiResults + tenorResults + googleResults,
                        secondary = fastResults
                    )
                )
                onProgressiveResults(combined)
                combined
            }
        }
    }

    private suspend fun fetchTenorGifImages(query: String): List<ImageResult> {
        return try {
            val stripped = query
                .replace(Regex("\\bgifs?\\b", RegexOption.IGNORE_CASE), "")
                .trim()
            val baseQuery = stripped.ifEmpty { query.trim() }
            if (baseQuery.isEmpty()) return emptyList()
            val slug = baseQuery.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("-")
            val encodedSlug = URLEncoder.encode(slug, "UTF-8").replace("+", "-")
            val html = httpGetString("https://tenor.com/search/$encodedSlug-gifs")
            SearchParsers.parseTenorSearchHtml(html)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchInstantHttpImages(
        effectiveQuery: String,
        filter: SearchFilter,
        nsfwMode: Boolean
    ): List<ImageResult> {
        return try {
            val encoded = URLEncoder.encode(effectiveQuery, "UTF-8")
            val qftFilter = when {
                filter.animatedOnly -> "&qft=+filterui:photo-animatedgif"
                filter.transparentOnly -> "&qft=+filterui:photo-transparent"
                else -> ""
            }
            val adltParam = if (nsfwMode) "off" else "moderate"
            val headers = if (nsfwMode) {
                mapOf("Cookie" to "SRCHHPGUSR=ADLT=OFF")
            } else {
                emptyMap()
            }
            val url =
                "https://www.bing.com/images/async?q=$encoded&first=0&count=35&adlt=$adltParam$qftFilter"
            val html = httpGetString(url, extraHeaders = headers)
            SearchParsers.parseBingAsyncImagesHtml(
                html = html,
                forceGifHint = filter.animatedOnly,
                forceTransparentHint = filter.transparentOnly
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchOptionalApiResults(
        effectiveQuery: String,
        filter: SearchFilter,
        nsfwMode: Boolean,
        apiKey: String
    ): List<ImageResult> {
        val encoded = URLEncoder.encode(effectiveQuery, "UTF-8")
        return try {
            if (apiKey.length >= 40 && apiKey.all { it.isLetterOrDigit() }) {
                val tbs = when {
                    filter.animatedOnly -> "&tbs=itp:animated"
                    filter.transparentOnly -> "&tbs=ic:trans"
                    else -> ""
                }
                val safe = if (nsfwMode) "off" else "active"
                val serpUrl =
                    "https://serpapi.com/search.json?engine=google_images&q=$encoded&safe=$safe$tbs&api_key=$apiKey"
                val json = httpGetString(serpUrl)
                val parsed = SearchParsers.parseSerpApiImagesJson(
                    jsonStr = json,
                    forceGifHint = filter.animatedOnly,
                    forceTransparentHint = filter.transparentOnly
                )
                if (parsed.isNotEmpty()) return parsed
            }
            val braveSafe = if (nsfwMode) "off" else "moderate"
            val braveUrl =
                "https://api.search.brave.com/res/v1/images/search?q=$encoded&safesearch=$braveSafe&count=30"
            val json = httpGetString(
                braveUrl,
                extraHeaders = mapOf("X-Subscription-Token" to apiKey)
            )
            SearchParsers.parseBraveImagesJson(
                jsonStr = json,
                forceGifHint = filter.animatedOnly,
                forceTransparentHint = filter.transparentOnly
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun httpGetString(
        urlStr: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val headers = buildMap {
            put(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            )
            put("Accept-Language", "en-US,en;q=0.9")
            putAll(extraHeaders)
        }
        return SafeNetworkClient.fetchStringBounded(
            url = urlStr,
            headers = headers,
            maxBytes = MAX_TEXT_RESPONSE_BYTES
        )
    }

    companion object {
        private const val MAX_TEXT_RESPONSE_BYTES = 5 * 1024 * 1024 // 5 MB
    }

    private fun mergeUnique(
        first: List<ImageResult>,
        second: List<ImageResult>
    ): List<ImageResult> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ImageResult>()
        for (item in first + second) {
            if (seen.add(item.fullImageUrl)) {
                out.add(item)
            }
        }
        return out
    }

    private fun interleaveResults(
        primary: List<ImageResult>,
        secondary: List<ImageResult>
    ): List<ImageResult> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ImageResult>()
        val maxLen = maxOf(primary.size, secondary.size)
        for (i in 0 until maxLen) {
            if (i < primary.size) {
                val p = primary[i]
                if (seen.add(p.fullImageUrl)) out.add(p)
            }
            if (i < secondary.size) {
                val s = secondary[i]
                if (seen.add(s.fullImageUrl)) out.add(s)
            }
        }
        return out
    }
}
