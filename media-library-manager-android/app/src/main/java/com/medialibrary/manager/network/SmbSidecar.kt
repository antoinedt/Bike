package com.medialibrary.manager.network

import com.medialibrary.manager.model.MediaMetadata
import com.medialibrary.manager.model.NetworkShare
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * Reads/writes a ".metadata.json" sidecar next to a media file on an SMB share, via jcifs-ng —
 * so fetched title/year/artwork travels with the file on the share itself, not just this app's
 * local cache. Any other device (or this one, after a reinstall) that browses the same share
 * sees it without re-fetching from iTunes.
 */
object SmbSidecar {

    private fun sidecarUrl(share: NetworkShare, relPath: String): String =
        SmbContextFactory.urlFor(share, "$relPath.metadata.json")

    suspend fun read(share: NetworkShare, relPath: String): MediaMetadata? = withContext(Dispatchers.IO) {
        runCatching {
            SmbFile(sidecarUrl(share, relPath), SmbContextFactory.contextFor(share)).inputStream.use {
                json.decodeFromString<MediaMetadata>(it.readBytes().toString(Charsets.UTF_8))
            }
        }.getOrNull()
    }

    suspend fun write(share: NetworkShare, relPath: String, metadata: MediaMetadata) = withContext(Dispatchers.IO) {
        SmbFile(sidecarUrl(share, relPath), SmbContextFactory.contextFor(share)).outputStream.use {
            it.write(json.encodeToString(metadata).toByteArray(Charsets.UTF_8))
        }
    }
}
