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
     * Waits until [readySelector] matches something in the rendered DOM (or gives up after
     * [timeoutMs] — deliberately not an error: a search with zero real results will legitimately
     * never match, and that's a valid outcome, not a failure), then returns the rendered HTML.
     * It does NOT wait for the page to finish loading: ad-heavy sites can take 20+ s to do that
     * (or never do) although their results are on screen within a second or two.
     */
    suspend fun fetchRenderedHtml(url: String, readySelector: String, timeoutMs: Long = 20_000): String? =
        withContext(Dispatchers.Main) {
            val webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Keep redirects inside this WebView instead of handing them to a browser app.
                webViewClient = WebViewClient()
            }
            try {
                webView.loadUrl(url)
                withTimeoutOrNull(timeoutMs) { waitForSelector(webView, readySelector) }
                extractHtml(webView)
            } finally {
                webView.stopLoading()
                webView.destroy()
            }
        }

    private suspend fun waitForSelector(webView: WebView, selector: String) {
        val escaped = JSONObject.quote(selector)
        while (true) {
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
