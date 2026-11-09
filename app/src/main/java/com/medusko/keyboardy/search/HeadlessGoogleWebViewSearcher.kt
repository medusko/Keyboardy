package com.medusko.keyboardy.search

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import kotlin.coroutines.resume

class HeadlessGoogleWebViewSearcher(
    private val context: Context,
    private val containerView: ViewGroup?
) {
    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeSearchId: Long = 0L

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(): WebView {
        webView?.let { return it }

        val wv = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            settings.loadsImagesAutomatically = false // Faster! We only need the image URLs from DOM/scripts
            settings.blockNetworkImage = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        }

        containerView?.let { parent ->
            if (wv.parent == null) {
                parent.addView(wv, ViewGroup.LayoutParams(1, 1))
            }
        }
        webView = wv
        return wv
    }

    suspend fun searchGoogleImages(
        query: String,
        filter: SearchFilter,
        nsfwMode: Boolean = false
    ): List<ImageResult> = withContext(Dispatchers.Main) {
        val effectiveQuery = filter.buildEffectiveQuery(query)
        if (effectiveQuery.isEmpty()) return@withContext emptyList()

        val encoded = URLEncoder.encode(effectiveQuery, "UTF-8")
        val tbsParam = when {
            filter.animatedOnly -> "&tbs=itp:animated"
            filter.transparentOnly -> "&tbs=ic:trans"
            else -> ""
        }
        val safeParam = if (nsfwMode) "off" else "active"
        val targetUrl = "https://www.google.com/search?q=$encoded&udm=2&hl=en&safe=$safeParam$tbsParam"

        val searchId = ++activeSearchId
        mainHandler.removeCallbacksAndMessages(null)

        withTimeoutOrNull(5500L) {
            suspendCancellableCoroutine { cont ->
                val wv = try {
                    getOrCreateWebView()
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(emptyList())
                    return@suspendCancellableCoroutine
                }

                var completed = false

                fun extractAndFinish() {
                    if (completed || !cont.isActive || activeSearchId != searchId) return
                    wv.evaluateJavascript(EXTRACTION_JS) { rawJson ->
                        if (completed || !cont.isActive || activeSearchId != searchId) return@evaluateJavascript
                        val parsed = SearchParsers.parseGoogleWebViewJson(
                            rawEvaluateJsResult = rawJson ?: "",
                            forceGifHint = filter.animatedOnly
                        )
                        if (parsed.isNotEmpty()) {
                            completed = true
                            cont.resume(parsed)
                        }
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val reqUrl = request?.url?.toString().orEmpty()
                        return !SearchParsers.isSafeHttpUrl(reqUrl)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (activeSearchId != searchId) return
                        // Allow 350ms for Google's inline script hydration after redirect/load
                        mainHandler.postDelayed({
                            extractAndFinish()
                        }, 350L)

                        // Second check at 1150ms in case Google did an enablejs redirect
                        mainHandler.postDelayed({
                            if (!completed && cont.isActive && activeSearchId == searchId) {
                                wv.evaluateJavascript(EXTRACTION_JS) { rawJson ->
                                    if (!completed && cont.isActive && activeSearchId == searchId) {
                                        completed = true
                                        cont.resume(
                                            SearchParsers.parseGoogleWebViewJson(
                                                rawEvaluateJsResult = rawJson ?: "",
                                                forceGifHint = filter.animatedOnly
                                            )
                                        )
                                    }
                                }
                            }
                        }, 1150L)
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        mainHandler.removeCallbacksAndMessages(null)
                        try {
                            (view?.parent as? ViewGroup)?.removeView(view)
                            view?.destroy()
                        } catch (_: Exception) {
                        }
                        if (webView === view) {
                            webView = null
                        }
                        if (!completed && cont.isActive) {
                            completed = true
                            cont.resume(emptyList())
                        }
                        return true
                    }
                }

                cont.invokeOnCancellation {
                    if (activeSearchId == searchId) {
                        stopActiveLoading()
                    }
                }

                wv.stopLoading()
                wv.evaluateJavascript("if (document.body) { document.body.innerHTML = ''; }", null)
                wv.loadUrl(targetUrl)
            }
        } ?: emptyList()
    }

    /**
     * Immediately aborts any ongoing webpage loading, script timers, and parsing on the UI thread
     * so that typing and UI operations are never stalled.
     */
    fun stopActiveLoading() {
        activeSearchId++
        mainHandler.removeCallbacksAndMessages(null)
        try {
            webView?.stopLoading()
        } catch (_: Exception) {
        }
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        webView?.let { wv ->
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.destroy()
            } catch (_: Exception) {
            }
        }
        webView = null
    }

    companion object {
        /**
         * JavaScript snippet injected into the headless Google Images WebView.
         * Extracts both:
         * 1. High-resolution URLs paired with encrypted-tbn0 thumbnails from Google's script blocks.
         * 2. Rendered <img> elements in the search grid.
         */
        private val EXTRACTION_JS = """
            (function() {
                var results = [];
                var seen = {};
                function addItem(thumb, full, title) {
                    var key = full || thumb;
                    if (!key || key.indexOf('http') !== 0 || seen[key]) return;
                    if (key.indexOf('google.com/images/branding') !== -1 || key.indexOf('gstatic.com/kp') !== -1) return;
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
                    var pairRegex = /\["(https:\/\/encrypted-tbn0\.gstatic\.com\/images\?q=tbn:[^"]+)",\d+,\d+\]\s*,\s*\["(https?:\/\/[^"]+)",\d+,\d+\]/g;
                    var m;
                    while ((m = pairRegex.exec(html)) !== null && results.length < 40) {
                        var tUrl = m[1].replace(/\\u003d/g, '=').replace(/\\u0026/g, '&');
                        var fUrl = m[2].replace(/\\u003d/g, '=').replace(/\\u0026/g, '&');
                        if (fUrl.indexOf('gstatic.com') === -1) {
                            addItem(tUrl, fUrl, 'Google Image');
                        }
                    }
                } catch (e) {}
                try {
                    var imgs = document.querySelectorAll('img');
                    for (var i = 0; i < imgs.length && results.length < 45; i++) {
                        var img = imgs[i];
                        var src = img.getAttribute('data-iurl') || img.getAttribute('data-src') || img.src || '';
                        var alt = img.alt || 'Google Image';
                        if (src.indexOf('https://encrypted-tbn0.gstatic.com/images') === 0 ||
                            (src.indexOf('http') === 0 && src.indexOf('google.com') === -1)) {
                            addItem(src, src, alt);
                        }
                    }
                } catch (e) {}
                return JSON.stringify(results);
            })();
        """.trimIndent()
    }
}
