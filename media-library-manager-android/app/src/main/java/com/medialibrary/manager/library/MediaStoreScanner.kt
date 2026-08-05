package com.medialibrary.manager.library

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaKind
import com.medialibrary.manager.model.MediaSourceType
import com.medialibrary.manager.model.classifyExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans the device's media via MediaStore (video/audio/images across all storage volumes the
 * OS indexes) rather than walking the filesystem directly — the right approach for Android 10+
 * scoped storage, and it doesn't need the broad MANAGE_EXTERNAL_STORAGE permission that a raw
 * fs walk would require. Requires READ_MEDIA_* (API 33+) or READ_EXTERNAL_STORAGE granted first.
 */
class MediaStoreScanner(private val context: Context) {

    suspend fun scan(): List<MediaItem> = withContext(Dispatchers.IO) {
        buildList {
            addAll(queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaKind.VIDEO))
            addAll(queryCollection(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaKind.AUDIO))
            addAll(queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaKind.IMAGE))
        }
    }

    private fun queryCollection(collection: android.net.Uri, expectedKind: MediaKind): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val extension = name.substringAfterLast('.', "")
                val kind = classifyExtension(extension)
                if (kind == MediaKind.OTHER) continue
                val uri = ContentUris.withAppendedId(collection, id)
                items += MediaItem(
                    name = name,
                    path = uri.toString(),
                    kind = kind,
                    extension = extension.lowercase(),
                    sizeBytes = cursor.getLong(sizeCol),
                    modifiedAtMillis = cursor.getLong(modifiedCol) * 1000L,
                    sourceType = MediaSourceType.LOCAL
                )
            }
        }
        return items
    }
}
