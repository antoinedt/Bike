package com.medialibrary.manager.torrent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID

/**
 * Hands a download off to whatever app the user has installed for it — this app never
 * downloads or manages torrents itself. Magnet links go straight to an ACTION_VIEW intent
 * (whatever's registered for the magnet: scheme, e.g. a torrent client); .torrent URLs are
 * downloaded to this app's cache first (Android has no equivalent of "open this remote URL in
 * another app" for arbitrary file types), then shared out via FileProvider so the OS can offer
 * a chooser of installed torrent apps. Mirrors the desktop app's shell.openExternal /
 * download-then-shell.openPath handoff.
 */
class TorrentHandoff(private val context: Context, private val httpClient: OkHttpClient = OkHttpClient()) {

    suspend fun handOff(url: String) {
        if (url.startsWith("magnet:")) {
            openView(Uri.parse(url), mimeType = null)
            return
        }

        require(url.startsWith("http://") || url.startsWith("https://")) {
            "Refusing to open unrecognized download URL scheme: $url"
        }

        val file = withContext(Dispatchers.IO) { download(url) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        openView(uri, mimeType = "application/x-bittorrent")
    }

    private fun download(url: String): File {
        val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            check(it.isSuccessful) { "Failed to download torrent file (status ${it.code})" }
            val body = it.body ?: error("Empty response body downloading $url")
            val dir = File(context.cacheDir, "torrents").apply { mkdirs() }
            val name = Uri.parse(url).lastPathSegment?.takeIf { n -> n.isNotBlank() } ?: "${UUID.randomUUID()}.torrent"
            val file = File(dir, if (name.endsWith(".torrent")) name else "$name.torrent")
            file.outputStream().use { out -> body.byteStream().copyTo(out) }
            return file
        }
    }

    private fun openView(uri: Uri, mimeType: String?) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (mimeType != null) setDataAndType(uri, mimeType) else data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("No app installed can handle this download — install a torrent client first.", e)
        }
    }
}
