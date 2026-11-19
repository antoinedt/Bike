package com.medialibrary.manager.network

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import jcifs.CIFSContext
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

private const val DOWNLOAD_SUBFOLDER = "MediaLibraryManager"

private val MIME_BY_EXTENSION = mapOf(
    "mp4" to "video/mp4", "mkv" to "video/x-matroska", "avi" to "video/x-msvideo",
    "mov" to "video/quicktime", "webm" to "video/webm", "m4v" to "video/x-m4v",
    "wmv" to "video/x-ms-wmv", "flv" to "video/x-flv", "mpg" to "video/mpeg", "mpeg" to "video/mpeg",
    "mp3" to "audio/mpeg", "flac" to "audio/flac", "wav" to "audio/wav", "m4a" to "audio/mp4",
    "ogg" to "audio/ogg", "aac" to "audio/aac", "wma" to "audio/x-ms-wma", "opus" to "audio/opus"
)

/**
 * Saves a full local copy of a network (SMB) file to the device's Downloads folder, under a
 * MediaLibraryManager subfolder — distinct from in-app streaming (SmbDataSource), which never
 * writes to disk.
 */
class NetworkDownloader(private val context: Context) {

    suspend fun download(name: String, extension: String, smbUrl: String, cifsContext: CIFSContext) =
        withContext(Dispatchers.IO) {
            val smbFile = SmbFile(smbUrl, cifsContext)
            smbFile.inputStream.use { input ->
                openDestination(name, mimeTypeFor(extension)).use { output ->
                    input.copyTo(output, bufferSize = 256 * 1024)
                }
            }
        }

    private fun openDestination(name: String, mimeType: String): OutputStream {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBFOLDER")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create a Downloads entry for \"$name\".")
            context.contentResolver.openOutputStream(uri)
                ?: throw IOException("Could not open the Downloads entry for \"$name\".")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_SUBFOLDER)
            if (!dir.exists()) dir.mkdirs()
            FileOutputStream(File(dir, name))
        }
    }

    private fun mimeTypeFor(extension: String): String = MIME_BY_EXTENSION[extension.lowercase()] ?: "application/octet-stream"
}
