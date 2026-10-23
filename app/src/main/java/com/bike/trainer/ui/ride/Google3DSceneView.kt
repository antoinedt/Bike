package com.bike.trainer.ui.ride

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import com.bike.trainer.di.ServiceLocator
import com.bike.trainer.route.Route
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * "Google 3D" scenery: Google Photorealistic 3D Tiles (real textured buildings,
 * trees and terrain) streamed live and rendered by CesiumJS in a WebView, with a
 * rider camera that follows the route. Needs internet and the Map Tiles API
 * enabled on the Google key; Google's terms don't allow storing it for offline use.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Google3DSceneView(
    route: Route,
    distanceMeters: Double,
    speedKmh: Double,
    chase: Boolean,
    apiKey: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var ready by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    val web = remember(route) {
        val main = Handler(Looper.getMainLooper())
        val proxy = TileProxy(context.applicationContext)
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(object {
                @JavascriptInterface
                fun onStatus(s: String) {
                    main.post {
                        when {
                            s == "ready" -> ready = true
                            s.startsWith("error:") && problem == null -> problem = explain(s.removePrefix("error:"))
                        }
                    }
                }
            }, "Android")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    return when (url.host) {
                        "appassets.androidplatform.net" -> assets.shouldInterceptRequest(url)
                        "tile.googleapis.com" -> proxy.fetch(request)
                        else -> null
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("setRoute(${routeJs(route)});", null)
                    val k = apiKey.replace("\\", "").replace("'", "")
                    view.evaluateJavascript("init('$k');", null)
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/google3d/index.html")
        }
    }

    SideEffect {
        web.evaluateJavascript(
            String.format(Locale.US, "update(%.2f,%.3f,%b);", distanceMeters, speedKmh / 3.6, chase),
            null,
        )
    }

    DisposableEffect(web, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> web.onResume()
                Lifecycle.Event.ON_PAUSE -> web.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        ServiceLocator.sceneView = web
        onDispose {
            lifecycle.removeObserver(observer)
            if (ServiceLocator.sceneView === web) ServiceLocator.sceneView = null
            web.stopLoading()
            web.destroy()
        }
    }

    Box(modifier) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { web })
        val msg = problem ?: if (!ready) "Loading Google 3D…" else null
        if (msg != null) {
            Text(
                msg,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(12.dp),
            )
        }
    }
}

/** Turn Cesium/Google errors into something actionable. */
private fun explain(raw: String): String = when {
    raw.contains("403") || raw.contains("PERMISSION_DENIED", true) || raw.contains("not authorized", true) ->
        "Google refused the 3D tiles. Enable \"Map Tiles API\" for your Google Maps key " +
            "in the Google Cloud console (and allow it in the key's API restrictions)."
    raw.contains("engine", true) -> raw
    else -> "Google 3D couldn't load: $raw"
}

/** The route as a compact JS literal: lat / lon / elevation every [Route.stepMeters]. */
private fun routeJs(route: Route): String {
    val lat = StringBuilder(); val lon = StringBuilder(); val ele = StringBuilder()
    route.points.forEachIndexed { i, p ->
        if (i > 0) { lat.append(','); lon.append(','); ele.append(',') }
        lat.append(String.format(Locale.US, "%.6f", p.lat))
        lon.append(String.format(Locale.US, "%.6f", p.lon))
        ele.append(String.format(Locale.US, "%.1f", p.elevation))
    }
    return "{lat:[$lat],lon:[$lon],ele:[$ele],step:${route.stepMeters}}"
}

/**
 * Fetches Google tile requests natively, adding the Android app-identity headers
 * an Android-restricted key requires (a WebView can't send them itself). Honours
 * HTTP cache headers only — no prefetching or offline storage.
 */
private class TileProxy(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cache(Cache(File(context.cacheDir, "google3d"), 128L * 1024 * 1024))
        .build()
    private val cert: String by lazy { signingSha1(context) }

    fun fetch(request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET") return null
        return try {
            val req = Request.Builder()
                .url(request.url.toString())
                .header("X-Android-Package", context.packageName)
                .header("X-Android-Cert", cert)
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body ?: return null
            val mime = resp.header("Content-Type")?.substringBefore(';')?.trim() ?: "application/octet-stream"
            WebResourceResponse(
                mime, null, resp.code, resp.message.ifBlank { if (resp.isSuccessful) "OK" else "Error" },
                mapOf("Access-Control-Allow-Origin" to "*"),
                body.byteStream(),
            )
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun signingSha1(context: Context): String = runCatching {
        val pm = context.packageManager
        val sig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return@runCatching ""
        MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
            .joinToString("") { String.format(Locale.US, "%02X", it) }
    }.getOrDefault("")
}
