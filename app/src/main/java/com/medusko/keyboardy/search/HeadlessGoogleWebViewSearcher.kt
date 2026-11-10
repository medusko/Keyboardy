package com.medusko.keyboardy.search

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import kotlin.coroutines.resume

class HeadlessGoogleWebViewSearcher(
    private val context: Context,
    private val containerView: ViewGroup?
) {
    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeSearchId: Long = 0L
    private var googleRateLimitedUntilMs: Long = 0L

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
        val googleUrl = "https://www.google.com/search?q=$encoded&udm=2&hl=en&safe=$safeParam$tbsParam"

        val qftParam = when {
            filter.animatedOnly -> "&qft=+filterui:photo-animatedgif"
            filter.transparentOnly -> "&qft=+filterui:photo-transparent"
            else -> ""
        }
        val bingWebUrl = "https://www.bing.com/images/search?q=$encoded&form=HDRSC2$qftParam"

        val searchId = ++activeSearchId
        mainHandler.removeCallbacksAndMessages(null)

        val startWithBing = System.currentTimeMillis() < googleRateLimitedUntilMs
        val initialUrl = if (startWithBing) bingWebUrl else googleUrl

        withTimeoutOrNull(6500L) {
            suspendCancellableCoroutine { cont ->
                val wv = try {
                    getOrCreateWebView()
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(emptyList())
                    return@suspendCancellableCoroutine
                }

                var completed = false
                var didFallbackToBing = startWithBing

                fun extractAndFinish(forceFallbackIfEmpty: Boolean) {
                    if (completed || !cont.isActive || activeSearchId != searchId) return
                    wv.evaluateJavascript(EXTRACTION_JS) { rawJson ->
                        if (completed || !cont.isActive || activeSearchId != searchId) return@evaluateJavascript
                        val parsed = SearchParsers.parseGoogleWebViewJson(
                            rawEvaluateJsResult = rawJson ?: "",
                            forceGifHint = filter.animatedOnly,
                            forceTransparentHint = filter.transparentOnly
                        )
                        if (parsed.isNotEmpty()) {
                            completed = true
                            cont.resume(parsed)
                        } else if (forceFallbackIfEmpty) {
                            if (!didFallbackToBing) {
                                googleRateLimitedUntilMs = System.currentTimeMillis() + 300_000L
                                didFallbackToBing = true
                                mainHandler.removeCallbacksAndMessages(null)
                                wv.stopLoading()
                                wv.loadUrl(bingWebUrl)
                            } else {
                                completed = true
                                cont.resume(emptyList())
                            }
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

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString().orEmpty()
                        val scheme = request?.url?.scheme?.lowercase().orEmpty()
                        if (scheme == "http" || scheme == "https") {
                            if (!SearchParsers.isSafeHttpUrl(reqUrl)) {
                                return WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }
                        } else if (scheme != "data" && scheme != "blob" && scheme != "about") {
                            return WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (activeSearchId != searchId || completed) return

                        // If Google redirected to /sorry CAPTCHA, fall back immediately to Bing Images WebView
                        if (url != null && url.contains("google.com/sorry") && !didFallbackToBing) {
                            googleRateLimitedUntilMs = System.currentTimeMillis() + 600_000L
                            didFallbackToBing = true
                            mainHandler.removeCallbacksAndMessages(null)
                            wv.stopLoading()
                            wv.loadUrl(bingWebUrl)
                            return
                        }

                        // Skip extraction while still on intermediate Google enablejs bounce
                        if (url != null && url.contains("/httpservice/retry/enablejs")) {
                            return
                        }

                        mainHandler.removeCallbacksAndMessages(null)
                        mainHandler.postDelayed({
                            extractAndFinish(forceFallbackIfEmpty = false)
                        }, 450L)

                        mainHandler.postDelayed({
                            extractAndFinish(forceFallbackIfEmpty = true)
                        }, 1350L)
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
                wv.loadUrl(initialUrl)
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
        private val EXTRACTION_JS = """
            (function() {
                var results = [];
                var seen = {};
                function addItem(thumb, full, title, srcBadge) {
                    var key = full || thumb;
                    if (!key || key.indexOf('http') !== 0 || seen[key]) return;
                    if (key.indexOf('.svg') !== -1 ||
                        key.indexOf('fonts.gstatic.com') !== -1 ||
                        key.indexOf('productlogos') !== -1 ||
                        key.indexOf('google.com/images/branding') !== -1 ||
                        key.indexOf('google.com/search') !== -1 ||
                        key.indexOf('gstatic.com/kp') !== -1) return;
                    if (key.indexOf('google.') !== -1 && key.indexOf('encrypted-tbn0.gstatic.com') === -1) return;
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
                                    addItem(obj.turl || obj.murl, obj.murl, obj.t || 'Image', '⚡');
                                }
                            } catch (e) {}
                        }
                        return JSON.stringify(results);
                    }
                } catch (e) {}
                try {
                    var html = document.documentElement.innerHTML;
                    var pairRegex = /\["(https:\/\/encrypted-tbn0\.gstatic\.com\/images\?q=tbn:[^"]+)",\d+,\d+\]\s*,\s*\["(https?:\/\/[^"]+)",\d+,\d+\]/g;
                    var m;
                    while ((m = pairRegex.exec(html)) !== null && results.length < 40) {
                        var tUrl = m[1].replace(/\\u003d/g, '=').replace(/\\u0026/g, '&');
                        var fUrl = m[2].replace(/\\u003d/g, '=').replace(/\\u0026/g, '&');
                        if (fUrl.indexOf('gstatic.com') === -1 && fUrl.indexOf('google.') === -1 && fUrl.indexOf('.svg') === -1) {
                            addItem(tUrl, fUrl, 'Google Image', 'G');
                        }
                    }
                } catch (e) {}
                try {
                    var imgs = document.querySelectorAll('img');
                    for (var i = 0; i < imgs.length && results.length < 45; i++) {
                        var img = imgs[i];
                        var w = img.naturalWidth || img.width || 0;
                        var h = img.naturalHeight || img.height || 0;
                        if ((w > 0 && w < 40) || (h > 0 && h < 40)) continue;
                        var src = img.getAttribute('data-iurl') || img.getAttribute('data-src') || img.src || '';
                        if (!src || src.indexOf('.svg') !== -1 || src.indexOf('fonts.gstatic.com') !== -1) continue;
                        var alt = img.alt || 'Google Image';
                        if (src.indexOf('https://encrypted-tbn0.gstatic.com/images') === 0 ||
                            (src.indexOf('http') === 0 && src.indexOf('google.') === -1 && src.indexOf('gstatic.com') === -1)) {
                            addItem(src, src, alt, 'G');
                        }
                    }
                } catch (e) {}
                return JSON.stringify(results);
            })();
        """.trimIndent()
    }
}
