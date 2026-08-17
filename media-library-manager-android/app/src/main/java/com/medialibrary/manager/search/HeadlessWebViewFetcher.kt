package com.medialibrary.manager.search

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Loads a URL in an off-screen WebView — the Android SDK's own Chromium-based engine, so no
 * extra headless-browser dependency is needed — so sites that render their results with
 * JavaScript actually finish rendering before we scrape them. Mirrors the desktop app's
 * headlessFetch.ts (hidden Electron BrowserWindow), same idea via the platform's own engine.
 *
 * WebView must be created and driven from the main thread; this class hops to Dispatchers.Main
 * internally so callers (e.g. SiteSearchRepository, running on Dispatchers.IO) don't have to.
 */
class HeadlessWebViewFetcher(private val context: Context) {

    /**
     * Waits until [readySelector] matches something in the rendered DOM (or gives up after a
     * bounded number of polls — deliberately not an error: a search with zero real results will
     * legitimately never match, and that's a valid outcome, not a failure), then returns the
     * fully-rendered HTML.
     */
    suspend fun fetchRenderedHtml(url: String, readySelector: String, timeoutMs: Long = 20_000): String? =
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                }
                try {
                    awaitPageLoad(webView, url)
                    waitForSelector(webView, readySelector)
                    extractHtml(webView)
                } finally {
                    webView.destroy()
                }
            }
        }

    private suspend fun awaitPageLoad(webView: WebView, url: String) = suspendCancellableCoroutine<Unit> { cont ->
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                if (cont.isActive) cont.resume(Unit)
            }
        }
        cont.invokeOnCancellation { webView.stopLoading() }
        webView.loadUrl(url)
    }

    private suspend fun waitForSelector(webView: WebView, selector: String) {
        val escaped = JSONObject.quote(selector)
        repeat(50) {
            val countText = evaluateJs(webView, "document.querySelectorAll($escaped).length")
            if ((countText.toIntOrNull() ?: 0) > 0) return
            delay(300)
        }
    }

    private suspend fun extractHtml(webView: WebView): String {
        val raw = evaluateJs(webView, "document.documentElement.outerHTML")
        // evaluateJavascript's callback result is a JSON-encoded string literal; unwrap it.
        return runCatching { JSONTokener(raw).nextValue() as String }.getOrDefault("")
    }

    private suspend fun evaluateJs(webView: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { result ->
                if (cont.isActive) cont.resume(result ?: "null")
            }
        }
}
