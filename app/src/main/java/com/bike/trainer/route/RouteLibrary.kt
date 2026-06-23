package com.bike.trainer.route

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * The on-device folder of GPX routes the rider can choose from. Files live in the
 * app's external files dir (Android/data/com.bike.trainer/files/routes), which is
 * reachable over USB / a file manager with no runtime permission — so the rider
 * can drop their own .gpx files in and they show up in the picker.
 *
 * Bundled examples (e.g. Paris–Roubaix) are seeded here on first use so they
 * appear in the list like any other route rather than as a special case.
 */
object RouteLibrary {

    private val SEED_ASSETS = listOf(
        "paris_roubaix.gpx",
        "alpe_dhuez.gpx",
        "mont_ventoux.gpx",
        "col_du_tourmalet.gpx",
        "col_du_galibier.gpx",
        "passo_stelvio.gpx",
        "passo_mortirolo.gpx",
        "sa_calobra.gpx",
        "haleakala.gpx",
        "grossglockner.gpx",
        "trollstigen.gpx",
    )

    fun folder(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "routes").apply { if (!exists()) mkdirs() }
    }

    /** Copy bundled example GPX files into the folder the first time. */
    fun ensureSeeded(context: Context) {
        val dir = folder(context)
        for (asset in SEED_ASSETS) {
            val dest = File(dir, asset)
            if (dest.exists()) continue
            runCatching {
                context.assets.open("routes/$asset").use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    /** All .gpx files in the folder, sorted by name. */
    fun list(context: Context): List<File> =
        folder(context).listFiles { f -> f.isFile && f.name.endsWith(".gpx", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /** Copy a picked GPX into the folder so it persists in the list. Returns the file. */
    fun importInto(context: Context, uri: Uri, displayName: String?): File? {
        val raw = (displayName ?: "route").substringBeforeLast('.')
        val safe = raw.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "route" }
        var dest = File(folder(context), "$safe.gpx")
        var i = 1
        while (dest.exists()) { dest = File(folder(context), "${safe}_$i.gpx"); i++ }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            dest
        }.getOrNull()
    }

    /** Human-friendly route name from a file (drops extension, underscores → spaces). */
    fun prettyName(file: File): String =
        file.name.substringBeforeLast('.').replace('_', ' ').trim()
}
