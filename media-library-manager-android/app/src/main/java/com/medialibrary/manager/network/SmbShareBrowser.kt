package com.medialibrary.manager.network

import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaKind
import com.medialibrary.manager.model.MediaSourceType
import com.medialibrary.manager.model.NetworkShare
import com.medialibrary.manager.model.classifyExtension
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_DEPTH = 10

/**
 * Lists an SMB share's contents via jcifs-ng. Unlike the Node smb2 client the desktop app is
 * stuck with, jcifs-ng's SmbFile exposes real size/mtime directly (length()/lastModified()) —
 * no separate raw-protocol workaround needed for that part.
 */
class SmbShareBrowser {

    suspend fun testConnection(share: NetworkShare): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            SmbFile(SmbContextFactory.rootUrl(share), SmbContextFactory.contextFor(share)).listFiles()
            Unit
        }
    }

    suspend fun scan(share: NetworkShare): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        val context = SmbContextFactory.contextFor(share)

        fun walk(url: String, relPath: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            val children = runCatching { SmbFile(url, context).listFiles() }.getOrNull() ?: return
            for (child in children) {
                val childName = child.name.trimEnd('/')
                val childRel = if (relPath.isEmpty()) childName else "$relPath/$childName"
                val isDir = runCatching { child.isDirectory }.getOrDefault(false)
                if (isDir) {
                    walk(child.url.toString(), childRel, depth + 1)
                    continue
                }
                val extension = childName.substringAfterLast('.', "")
                val kind = classifyExtension(extension)
                if (kind == MediaKind.OTHER) continue
                items += MediaItem(
                    name = childName,
                    path = "smb://${share.id}/$childRel",
                    kind = kind,
                    extension = extension.lowercase(),
                    sizeBytes = runCatching { child.length() }.getOrDefault(0L),
                    modifiedAtMillis = runCatching { child.lastModified() }.getOrDefault(0L),
                    sourceType = MediaSourceType.NETWORK,
                    shareId = share.id
                )
            }
        }

        walk(SmbContextFactory.rootUrl(share), "", 0)
        items
    }
}
