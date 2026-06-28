package com.bike.trainer.data

import android.content.Context
import com.bike.trainer.route.RouteLibrary
import com.bike.trainer.route.StreetViewCache
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A portable snapshot of the user's settings — rider profiles + stats, in-app
 * keys, and ride preferences — that can be written to a file (e.g. saved to
 * Google Drive via the system picker) and restored after an update/reinstall.
 *
 * Strava OAuth tokens are intentionally NOT included; the user re-connects.
 */
@Serializable
data class BackupBundle(
    val version: Int = 1,
    val profiles: ProfilesState = ProfilesState(),
    val mapTilesKey: String = "",
    val gearCount: Int = 12,
    val difficulty: String = "ROLLING",
)

class BackupManager(
    private val profiles: ProfileRepository,
    private val config: AppConfigRepository,
    private val settings: SettingsRepository,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Serialize the current settings to a JSON string. */
    suspend fun export(): String {
        val cfg = config.current()
        val s = settings.settings.first()
        val bundle = BackupBundle(
            profiles = profiles.current(),
            mapTilesKey = cfg.mapTilesKey,
            gearCount = s.gearCount,
            difficulty = s.difficulty.name,
        )
        return json.encodeToString(bundle)
    }

    /** Restore settings from a backup JSON string. Returns true on success. */
    suspend fun import(raw: String): Boolean {
        val bundle = try {
            json.decodeFromString<BackupBundle>(raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("Backup data couldn't be read (${e.message})", e)
        }
        profiles.replaceAll(bundle.profiles)
        config.setMapTilesKey(bundle.mapTilesKey)
        settings.setGearCount(bundle.gearCount)
        runCatching { com.bike.trainer.route.RouteGenerator.Difficulty.valueOf(bundle.difficulty) }
            .getOrNull()?.let { settings.setDifficulty(it) }
        return true
    }

    /**
     * Write a full ZIP backup: the settings JSON plus the GPX routes folder and
     * the prefetched Street View frames, so a single file carries everything.
     */
    suspend fun exportZip(context: Context, out: OutputStream) {
        val jsonStr = export()
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_JSON))
            zip.write(jsonStr.toByteArray())
            zip.closeEntry()
            addTree(zip, RouteLibrary.folder(context), "routes")
            addTree(zip, StreetViewCache.cacheRoot(context), "svcache")
        }
    }

    /** Restore from a ZIP backup (settings + routes + prefetched frames). */
    suspend fun importZip(context: Context, input: InputStream): Boolean {
        var jsonStr: String? = null
        val routesDir = RouteLibrary.folder(context)
        val svDir = StreetViewCache.cacheRoot(context)
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && !name.contains("..")) {
                    when {
                        name == BACKUP_JSON -> jsonStr = zip.readBytes().decodeToString()
                        // Route / Street-View files are best-effort: a single bad
                        // file must not fail the whole restore (the settings JSON is
                        // what matters most).
                        name.startsWith("routes/") ->
                            runCatching { extract(zip, File(routesDir, name.removePrefix("routes/"))) }
                        name.startsWith("svcache/") ->
                            runCatching { extract(zip, File(svDir, name.removePrefix("svcache/"))) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return jsonStr?.let { import(it) }
            ?: throw IllegalArgumentException("No backup.json found in the archive")
    }

    /** Restore from a stream that is either a ZIP backup or a legacy plain JSON. */
    suspend fun importAuto(context: Context, input: InputStream): Boolean {
        val buf = input.buffered()
        buf.mark(2)
        val b0 = buf.read()
        val b1 = buf.read()
        buf.reset()
        val isZip = b0 == 'P'.code && b1 == 'K'.code
        return if (isZip) importZip(context, buf) else import(buf.readBytes().decodeToString())
    }

    /** Restore from raw bytes (kept for callers that already hold the bytes). */
    suspend fun importAuto(context: Context, bytes: ByteArray): Boolean =
        importAuto(context, bytes.inputStream())

    private fun addTree(zip: ZipOutputStream, root: File, prefix: String) {
        if (!root.exists()) return
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
            zip.putNextEntry(ZipEntry("$prefix/$rel"))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun extract(zip: ZipInputStream, dest: File) {
        dest.parentFile?.mkdirs()
        dest.outputStream().use { zip.copyTo(it) }
    }

    private companion object {
        const val BACKUP_JSON = "backup.json"
    }
}
