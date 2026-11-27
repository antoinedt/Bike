package com.medialibrary.manager.log

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * OmniPlay's own log (searches, crashes…): files/logs/omniplay.log in the app's private storage,
 * shared from Settings → Share log. Crashes are written here before the app closes, so the
 * next launch can still send the reason.
 */
object AppLog {
    private const val MAX_BYTES = 1_000_000L
    private var file: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "omniplay.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            log("crash", "on thread ${thread.name}:\n${stackTrace(error)}")
            previous?.uncaughtException(thread, error)
        }
    }

    @Synchronized
    fun log(area: String, message: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, "omniplay.log.old"))
            f.appendText("${Instant.now()} [$area] $message\n")
        }
    }

    /** Empties the log (and drops the previous one). */
    @Synchronized
    fun clear() {
        val f = file ?: return
        runCatching {
            f.writeText("")
            File(f.parentFile, "omniplay.log.old").delete()
        }
    }

    fun stackTrace(error: Throwable): String = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()

    /** Opens the share sheet with the log attached (email it, save it to Drive…). */
    fun share(context: Context) {
        val f = file ?: return
        if (!f.exists()) f.writeText("")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "OmniPlay log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share OmniPlay log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
