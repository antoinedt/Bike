package com.medialibrary.manager.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.medialibrary.manager.model.AppSettings
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaSourceType
import com.medialibrary.manager.model.NetworkShare
import com.medialibrary.manager.model.TransferTarget
import com.medialibrary.manager.network.SmbContextFactory
import jcifs.smb.SmbFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

const val DEVICE_DOWNLOADS_SUBFOLDER = "MediaLibraryManager"

private val MIME_BY_EXTENSION = mapOf(
    "mp4" to "video/mp4", "mkv" to "video/x-matroska", "avi" to "video/x-msvideo",
    "mov" to "video/quicktime", "webm" to "video/webm", "m4v" to "video/x-m4v",
    "wmv" to "video/x-ms-wmv", "flv" to "video/x-flv", "mpg" to "video/mpeg", "mpeg" to "video/mpeg",
    "mp3" to "audio/mpeg", "flac" to "audio/flac", "wav" to "audio/wav", "m4a" to "audio/mp4",
    "ogg" to "audio/ogg", "aac" to "audio/aac", "wma" to "audio/x-ms-wma", "opus" to "audio/opus",
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
    "webp" to "image/webp", "srt" to "application/x-subrip"
)

fun mimeTypeFor(name: String): String = MIME_BY_EXTENSION[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

/** "Movie (2).mkv" for the 2nd attempt at "Movie.mkv". */
fun numberedName(name: String, n: Int): String {
    if (n <= 1) return name
    val dot = name.lastIndexOf('.')
    return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
}

class TransferException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reading from and writing into the three kinds of place the app knows: this device's Downloads
 * folder (MediaStore), user-picked local folders (Storage Access Framework trees) and SMB shares
 * (jcifs-ng). Never overwrites: an existing name gets " (2)", " (3)"… appended.
 */
class TransferStorage(private val context: Context) {

    fun label(target: TransferTarget, settings: AppSettings): String {
        val base = when (target.type) {
            TransferTarget.FOLDER -> settings.localFolders.find { it.id == target.id }?.label?.let { "This device · $it" } ?: "Removed folder"
            TransferTarget.SHARE -> settings.networkShares.find { it.id == target.id }?.label ?: "Removed share"
            else -> "This device · Downloads/$DEVICE_DOWNLOADS_SUBFOLDER"
        }
        return if (target.relPath.isEmpty()) base else "$base/${target.relPath}"
    }

    /** Device Downloads can't be browsed into (Android only lets an app see its own files there). */
    fun canBrowse(target: TransferTarget) = target.type != TransferTarget.DEVICE_DOWNLOADS

    /** Names of the folders directly inside [target], sorted. */
    fun listFolders(target: TransferTarget, settings: AppSettings): List<String> = when (target.type) {
        TransferTarget.SHARE -> {
            val share = shareFor(target, settings)
            SmbFile(dirUrl(share, target.relPath), SmbContextFactory.contextFor(share)).listFiles()
                .filter { runCatching { it.isDirectory }.getOrDefault(false) }
                .map { it.name.trimEnd('/') }
                .filterNot { it.startsWith(".") }
        }
        TransferTarget.FOLDER -> {
            val dir = treeDir(target, settings, create = false)
            dir.listFiles().filter { it.isDirectory }.mapNotNull { it.name }.filterNot { it.startsWith(".") }
        }
        else -> emptyList()
    }.sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun createFolder(target: TransferTarget, settings: AppSettings, name: String) {
        val clean = cleanName(name)
        when (target.type) {
            TransferTarget.SHARE -> {
                val share = shareFor(target, settings)
                SmbFile(dirUrl(share, target.child(clean).relPath), SmbContextFactory.contextFor(share)).mkdirs()
            }
            TransferTarget.FOLDER -> treeDir(target.child(clean), settings, create = true)
            else -> throw TransferException("Can't create folders in Downloads.")
        }
    }

    /** Opens a library item for reading; returns the stream and its size (-1 if unknown). */
    fun openSource(item: MediaItem, settings: AppSettings): Pair<InputStream, Long> {
        if (item.sourceType == MediaSourceType.NETWORK) {
            val share = settings.networkShares.find { it.id == item.shareId }
                ?: throw TransferException("The network share for \"${item.name}\" is no longer configured.")
            val relPath = item.path.removePrefix("smb://${share.id}/")
            val file = SmbFile(SmbContextFactory.urlFor(share, relPath), SmbContextFactory.contextFor(share))
            return file.inputStream to runCatching { file.length() }.getOrDefault(item.sizeBytes)
        }
        val input = context.contentResolver.openInputStream(Uri.parse(item.path))
            ?: throw TransferException("Couldn't open \"${item.name}\".")
        return input to item.sizeBytes
    }

    /**
     * Copies [input] to [relFile] ("Movie.mkv", or "Show/ep1.mkv" to create the folder too) inside
     * [target]. Returns the name it was saved under (different from the requested one if that was
     * taken).
     */
    fun write(
        target: TransferTarget,
        settings: AppSettings,
        relFile: String,
        input: InputStream,
        totalBytes: Long,
        onProgress: (Long) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): String {
        val parts = relFile.split('/').filter { it.isNotEmpty() }.map(::cleanName)
        require(parts.isNotEmpty()) { "empty file name" }
        val dirTarget = parts.dropLast(1).fold(target) { t, p -> t.child(p) }
        val requestedName = parts.last()
        val (name, output) = when (target.type) {
            TransferTarget.SHARE -> openSmb(dirTarget, settings, requestedName)
            TransferTarget.FOLDER -> openTree(dirTarget, settings, requestedName)
            else -> openDeviceDownloads(dirTarget.relPath, requestedName)
        }
        var completed = false
        try {
            output.use { out -> copy(input, out, onProgress, isCancelled) }
            completed = true
        } finally {
            if (!completed) runCatching { deletePartial(dirTarget, settings, name) }
        }
        return name
    }

    private fun copy(input: InputStream, output: OutputStream, onProgress: (Long) -> Unit, isCancelled: () -> Boolean) {
        val buffer = ByteArray(1024 * 1024)
        var copied = 0L
        var lastReport = 0L
        while (true) {
            if (isCancelled()) throw TransferException("Cancelled.")
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            copied += read
            if (copied - lastReport >= 512 * 1024) {
                onProgress(copied)
                lastReport = copied
            }
        }
        onProgress(copied)
    }

    private fun openSmb(dir: TransferTarget, settings: AppSettings, requested: String): Pair<String, OutputStream> {
        val share = shareFor(dir, settings)
        val ctx = SmbContextFactory.contextFor(share)
        val dirUrl = dirUrl(share, dir.relPath)
        SmbFile(dirUrl, ctx).let { if (!it.exists()) it.mkdirs() }
        for (n in 1..999) {
            val name = numberedName(requested, n)
            val file = SmbFile(dirUrl + name, ctx)
            if (!file.exists()) return name to file.outputStream
        }
        throw TransferException("Too many files called \"$requested\" already.")
    }

    private fun openTree(dir: TransferTarget, settings: AppSettings, requested: String): Pair<String, OutputStream> {
        val folder = treeDir(dir, settings, create = true)
        val existing = folder.listFiles().mapNotNull { it.name }.toSet()
        val name = (1..999).map { numberedName(requested, it) }.firstOrNull { it !in existing }
            ?: throw TransferException("Too many files called \"$requested\" already.")
        // octet-stream so the provider keeps the exact name instead of adding its own extension.
        val created = folder.createFile("application/octet-stream", name)
            ?: throw TransferException("Couldn't create \"$name\" in ${folder.name}.")
        val output = context.contentResolver.openOutputStream(created.uri)
            ?: throw TransferException("Couldn't write \"$name\".")
        return (created.name ?: name) to output
    }

    private fun openDeviceDownloads(relDir: String, requested: String): Pair<String, OutputStream> {
        val subPath = listOf(DEVICE_DOWNLOADS_SUBFOLDER, relDir).filter { it.isNotEmpty() }.joinToString("/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore renames on its own if the name is taken.
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, requested)
                put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(requested))
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subPath")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw TransferException("Couldn't create \"$requested\" in Downloads.")
            val output = context.contentResolver.openOutputStream(uri)
                ?: throw TransferException("Couldn't write \"$requested\" in Downloads.")
            return requested to output
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subPath)
        dir.mkdirs()
        val name = (1..999).map { numberedName(requested, it) }.firstOrNull { !File(dir, it).exists() }
            ?: throw TransferException("Too many files called \"$requested\" already.")
        return name to FileOutputStream(File(dir, name))
    }

    private fun deletePartial(dir: TransferTarget, settings: AppSettings, name: String) {
        when (dir.type) {
            TransferTarget.SHARE -> {
                val share = shareFor(dir, settings)
                SmbFile(dirUrl(share, dir.relPath) + name, SmbContextFactory.contextFor(share)).delete()
            }
            TransferTarget.FOLDER -> treeDir(dir, settings, create = false).findFile(name)?.delete()
            else -> Unit // MediaStore: left as-is; the system cleans up pending entries.
        }
    }

    private fun shareFor(target: TransferTarget, settings: AppSettings): NetworkShare =
        settings.networkShares.find { it.id == target.id } ?: throw TransferException("That network share is no longer configured.")

    private fun dirUrl(share: NetworkShare, relPath: String): String {
        val rel = relPath.trim('/')
        return SmbContextFactory.rootUrl(share) + if (rel.isEmpty()) "" else "$rel/"
    }

    private fun treeDir(target: TransferTarget, settings: AppSettings, create: Boolean): DocumentFile {
        val folder = settings.localFolders.find { it.id == target.id } ?: throw TransferException("That folder is no longer configured.")
        val hasWrite = context.contentResolver.persistedUriPermissions.any { it.uri.toString() == folder.treeUri && it.isWritePermission }
        if (create && !hasWrite) {
            throw TransferException("No permission to save into \"${folder.label}\". In Settings, use Change folder… on it and pick it again to allow that.")
        }
        var dir = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri)) ?: throw TransferException("Can't open \"${folder.label}\".")
        for (part in target.relPath.split('/').filter { it.isNotEmpty() }) {
            dir = dir.findFile(part)?.takeIf { it.isDirectory }
                ?: if (create) dir.createDirectory(part) ?: throw TransferException("Couldn't create folder \"$part\".")
                else throw TransferException("Folder \"$part\" not found.")
        }
        return dir
    }

    private fun cleanName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimEnd('.')
        if (cleaned.isEmpty() || cleaned == "..") throw TransferException("\"$name\" isn't a usable name.")
        return cleaned
    }
}
