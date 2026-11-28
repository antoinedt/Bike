package com.medialibrary.manager.network

import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaKind
import com.medialibrary.manager.model.MediaSourceType
import com.medialibrary.manager.model.NetworkShare
import com.medialibrary.manager.model.classifyExtension
import jcifs.smb.SmbFile
import com.medialibrary.manager.library.SourceScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val MAX_DEPTH = 10
/** Folders listed at the same time on one share. */
private const val CONCURRENCY = 4

/**
 * Lists an SMB share's contents via jcifs-ng. Unlike the Node smb2 client the desktop app is
 * stuck with, jcifs-ng's SmbFile exposes real size/mtime directly (length()/lastModified()) —
 * no separate raw-protocol workaround needed for that part.
 */
class SmbShareBrowser {

    suspend fun testConnection(share: NetworkShare): Result<Unit> = withContext(Dispatchers.IO) {
        SmbContextFactory.forgetHostChoices()
        fun attempt() = runCatching {
            SmbFile(SmbContextFactory.rootUrl(share), SmbContextFactory.contextFor(share)).listFiles()
            Unit
        }
        // One retry: over ZeroTier/VPN links the first connection can fail while the route warms up.
        val result = attempt().let { first -> if (first.isSuccess) first else attempt() }
        result.exceptionOrNull()?.let {
            com.medialibrary.manager.log.AppLog.log("share", "test ${share.label} (${share.host}) failed: ${causeChain(it)}")
        }
        result.fold({ Result.success(Unit) }, { Result.failure(Exception(friendlySmbError(share, it), it)) })
    }

    /**
     * Scans the share for media, listing a few folders at a time and handing each folder's media to
     * [onBatch] as soon as it's read, so the library fills in while the scan runs. Throws if the share
     * itself can't be reached (offline, wrong password), so callers keep what the library already has.
     */
    suspend fun scan(
        share: NetworkShare,
        onBatch: (List<MediaItem>) -> Unit = {},
        excluded: Set<String> = emptySet()
    ): SourceScanResult = withContext(Dispatchers.IO) {
        val context = SmbContextFactory.contextFor(share)
        val seen = ConcurrentHashMap.newKeySet<String>()
        val permits = Semaphore(CONCURRENCY)
        // The root is listed up front and allowed to throw: an unreachable share must not look empty.
        val root = SmbFile(SmbContextFactory.rootUrl(share), context).listFiles()

        suspend fun walk(children: Array<SmbFile>, relPath: String, depth: Int) {
            coroutineScope {
                val batch = ArrayList<MediaItem>()
                for (child in children) {
                    val childName = child.name.trimEnd('/')
                    val childRel = if (relPath.isEmpty()) childName else "$relPath/$childName"
                    val isDir = runCatching { child.isDirectory }.getOrDefault(false)
                    if (isDir) {
                        if (depth + 1 > MAX_DEPTH) continue
                        if ("network:${share.id}:$childRel" in excluded) continue
                        launch {
                            // Only the listing holds a permit, never the recursion — no deadlock.
                            val sub = permits.withPermit { runCatching { child.listFiles() }.getOrNull() }
                            if (sub != null) walk(sub, childRel, depth + 1)
                        }
                        continue
                    }
                    val extension = childName.substringAfterLast('.', "")
                    val kind = classifyExtension(extension)
                    if (kind == MediaKind.OTHER) continue
                    val mediaPath = "smb://${share.id}/$childRel"
                    batch += MediaItem(
                        // Stable (not the random default) so a rescan's items line up by id with what
                        // was there before — ItemCacheRepository relies on that to carry forward
                        // fetched artwork/info instead of silently losing it on every rescan.
                        id = mediaPath,
                        name = childName,
                        path = mediaPath,
                        kind = kind,
                        extension = extension.lowercase(),
                        sizeBytes = runCatching { child.length() }.getOrDefault(0L),
                        modifiedAtMillis = runCatching { child.lastModified() }.getOrDefault(0L),
                        sourceType = MediaSourceType.NETWORK,
                        shareId = share.id,
                        folderPath = relPath
                    )
                }
                if (batch.isNotEmpty()) {
                    batch.forEach { seen += it.id }
                    onBatch(batch)
                }
            }
        }

        walk(root, "", 0)
        SourceScanResult(complete = true, seenIds = seen)
    }
}

/** "Failed to connect: 0.0.0.0<00>/172.28.214.162" (jcifs' label for a server given by IP) -> plain words. */
fun friendlySmbError(share: NetworkShare, error: Throwable): String {
    val message = generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString(" / ")
    return when {
        Regex("Failed to connect|ConnectException|timed out|unreachable|No route", RegexOption.IGNORE_CASE).containsMatchIn(message) ->
            "Can't reach ${share.host}${if (share.backupHost.isNotBlank()) " or ${share.backupHost}" else ""}. Check the phone is on the same network (or ZeroTier is on, and no other VPN), " +
                "the computer is awake, and its firewall allows file sharing on that network."
        Regex("LOGON_FAILURE|Logon failure|ACCESS_DENIED", RegexOption.IGNORE_CASE).containsMatchIn(message) ->
            "${share.host} refused the username or password."
        Regex("BAD_NETWORK_NAME", RegexOption.IGNORE_CASE).containsMatchIn(message) ->
            "${share.host} has no share called \"${share.share}\"."
        else -> error.message ?: message
    }
}

/** "SmbException: Failed to connect … ← ConnectException: … ETIMEDOUT" — the root cause is what tells timeout / refused / unreachable apart. */
fun causeChain(error: Throwable): String =
    generateSequence(error) { it.cause }.take(6).joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message}" }
