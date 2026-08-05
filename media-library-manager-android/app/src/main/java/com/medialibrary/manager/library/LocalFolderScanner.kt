package com.medialibrary.manager.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.medialibrary.manager.model.LocalFolder
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaKind
import com.medialibrary.manager.model.MediaSourceType
import com.medialibrary.manager.model.classifyExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_DEPTH = 12

/**
 * Scans a user-picked folder tree (Storage Access Framework) for media files. This is how
 * "which folders are considered" gets scoped on Android — MediaStoreScanner only sees the
 * whole device, with no folder-level control.
 */
class LocalFolderScanner(private val context: Context) {

    suspend fun scan(folder: LocalFolder): List<MediaItem> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri)) ?: return@withContext emptyList()
        val items = mutableListOf<MediaItem>()
        walk(root, 0, items)
        items
    }

    private fun walk(dir: DocumentFile, depth: Int, out: MutableList<MediaItem>) {
        if (depth > MAX_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (child.isDirectory) {
                walk(child, depth + 1, out)
                continue
            }
            if (!child.isFile) continue
            val name = child.name ?: continue
            val extension = name.substringAfterLast('.', "")
            val kind = classifyExtension(extension)
            if (kind == MediaKind.OTHER) continue
            out += MediaItem(
                name = name,
                path = child.uri.toString(),
                kind = kind,
                extension = extension.lowercase(),
                sizeBytes = child.length(),
                modifiedAtMillis = child.lastModified(),
                sourceType = MediaSourceType.LOCAL
            )
        }
    }
}
