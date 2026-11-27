package com.medialibrary.manager.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.medialibrary.manager.model.LocalFolder
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaKind
import com.medialibrary.manager.model.MediaSourceType
import com.medialibrary.manager.model.classifyExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val MAX_DEPTH = 12
/** Folders read at the same time within one library folder. */
private const val CONCURRENCY = 4

/** What a finished scan of one folder or share found. */
data class SourceScanResult(
    /** False when the folder itself couldn't be read (permission revoked, card removed): the caller
     *  must then keep what the library already has instead of treating everything as gone. */
    val complete: Boolean,
    val seenIds: Set<String>
)

/**
 * Scans a user-picked folder tree (Storage Access Framework) for media files. This is how
 * "which folders are considered" gets scoped on Android — MediaStoreScanner only sees the
 * whole device, with no folder-level control.
 *
 * Each folder is read with a single DocumentsContract query (name, type, size, date in one go —
 * DocumentFile would make several queries per file), a few folders at a time, and each folder's
 * media goes to [onBatch] as soon as it's read so the library fills in while the scan runs.
 */
class LocalFolderScanner(private val context: Context) {

    private class Child(val documentId: String, val name: String, val isDirectory: Boolean, val size: Long, val modified: Long)

    suspend fun scan(folder: LocalFolder, onBatch: (List<MediaItem>) -> Unit = {}): SourceScanResult = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(folder.treeUri)
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext SourceScanResult(complete = false, seenIds = emptySet())
        val seen = ConcurrentHashMap.newKeySet<String>()
        val permits = Semaphore(CONCURRENCY)
        var rootReadable = true

        suspend fun walk(documentId: String, relPath: String, depth: Int) {
            // Only the query holds a permit, never the recursion — so subfolders can't deadlock.
            val children = permits.withPermit { list(treeUri, documentId) }
            if (children == null) {
                if (depth == 0) rootReadable = false
                return
            }
            coroutineScope {
                val batch = ArrayList<MediaItem>()
                for (child in children) {
                    val childRel = if (relPath.isEmpty()) child.name else "$relPath/${child.name}"
                    if (child.isDirectory) {
                        if (depth + 1 <= MAX_DEPTH) launch { walk(child.documentId, childRel, depth + 1) }
                        continue
                    }
                    val extension = child.name.substringAfterLast('.', "")
                    val kind = classifyExtension(extension)
                    if (kind == MediaKind.OTHER) continue
                    // Same uri DocumentFile gave (content://…/tree/<root>/document/<id>), so ids stay
                    // stable and ItemCacheRepository keeps lining fetched artwork/info up with them.
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId).toString()
                    batch += MediaItem(
                        id = uri,
                        name = child.name,
                        path = uri,
                        kind = kind,
                        extension = extension.lowercase(),
                        sizeBytes = child.size,
                        modifiedAtMillis = child.modified,
                        sourceType = MediaSourceType.LOCAL,
                        folderId = folder.id,
                        folderPath = relPath
                    )
                }
                if (batch.isNotEmpty()) {
                    batch.forEach { seen += it.id }
                    onBatch(batch)
                }
            }
        }

        walk(rootId, "", 0)
        SourceScanResult(complete = rootReadable, seenIds = seen)
    }

    /** One folder's children, or null if it couldn't be read. */
    private fun list(treeUri: Uri, documentId: String): List<Child>? = runCatching {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val out = ArrayList<Child>(cursor.count)
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1)
                if (id == null || name == null) continue
                out += Child(
                    documentId = id,
                    name = name,
                    isDirectory = cursor.getString(2) == Document.MIME_TYPE_DIR,
                    size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                    modified = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                )
            }
            out
        }
    }.getOrNull()
}
