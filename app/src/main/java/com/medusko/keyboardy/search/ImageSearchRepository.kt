package com.medusko.keyboardy.search

import com.medusko.keyboardy.prefs.SearchEngineMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
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

        when (engineMode) {
            SearchEngineMode.INSTANT_HTTP -> {
                val fast = fetchInstantHttpImages(effectiveQuery, filter, nsfwMode)
                val apiItems = apiDeferred?.await().orEmpty()
                val merged = mergeUnique(apiItems, fast)
                onProgressiveResults(merged)
                merged
            }

            SearchEngineMode.GOOGLE_WEBVIEW -> {
                val googleItems = headlessGoogleSearcher.searchGoogleImages(query, filter, nsfwMode)
                val finalItems = if (googleItems.isNotEmpty()) {
                    googleItems
                } else {
                    fetchInstantHttpImages(effectiveQuery, filter, nsfwMode)
                }
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
                val apiResults = apiDeferred?.await().orEmpty()
                val initialList = mergeUnique(apiResults, fastResults)
                if (initialList.isNotEmpty()) {
                    onProgressiveResults(initialList)
                }

                // Await Google Headless WebView results and interleave/merge them
                val googleResults = googleDeferred.await()
                val combined = interleaveResults(
                    primary = apiResults + googleResults,
                    secondary = fastResults
                )
                onProgressiveResults(combined)
                combined
            }
        }
    }

    private suspend fun fetchInstantHttpImages(
        effectiveQuery: String,
        filter: SearchFilter,
        nsfwMode: Boolean
    ): List<ImageResult> = withContext(Dispatchers.IO) {
        try {
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
            SearchParsers.parseBingAsyncImagesHtml(html, forceGifHint = filter.animatedOnly)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchOptionalApiResults(
        effectiveQuery: String,
        filter: SearchFilter,
        nsfwMode: Boolean,
        apiKey: String
    ): List<ImageResult> {
        val encoded = URLEncoder.encode(effectiveQuery, "UTF-8")
        return try {
            if (apiKey.length >= 40 && apiKey.all { it.isLetterOrDigit() }) {
                val tbs = if (filter.animatedOnly) "&tbs=itp:animated" else ""
                val safe = if (nsfwMode) "off" else "active"
                val serpUrl =
                    "https://serpapi.com/search.json?engine=google_images&q=$encoded&safe=$safe$tbs&api_key=$apiKey"
                val json = httpGetString(serpUrl)
                val parsed = SearchParsers.parseSerpApiImagesJson(json, filter.animatedOnly)
                if (parsed.isNotEmpty()) return parsed
            }
            val braveSafe = if (nsfwMode) "off" else "moderate"
            val braveUrl =
                "https://api.search.brave.com/res/v1/images/search?q=$encoded&safesearch=$braveSafe&count=30"
            val json = httpGetString(
                braveUrl,
                extraHeaders = mapOf("X-Subscription-Token" to apiKey)
            )
            SearchParsers.parseBraveImagesJson(json, filter.animatedOnly)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun httpGetString(
        urlStr: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4500
            readTimeout = 4500
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            )
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            for ((k, v) in extraHeaders) {
                setRequestProperty(k, v)
            }
        }
        return try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
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
