package com.medialibrary.manager.artwork

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Grabs a real frame from a local video as its "artwork" — a far better fallback than any online
 * catalog for a home media library, where most files (camera footage, home videos) will never
 * have a match on iTunes. Local items only: MediaMetadataRetriever needs either a Context+Uri or
 * a raw file descriptor, neither of which an SMB share offers without a custom MediaDataSource —
 * deferred as a follow-up, not implemented here. Returns a data: URL (no file written, no network
 * request) that Coil/AsyncImage can load directly like any other image URL.
 */
object VideoThumbnailExtractor {

    suspend fun extract(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (durationMs == null || durationMs <= 0) return@withContext null
            // A bit into the clip avoids black/blank opening frames common in home videos.
            val frameTimeUs = (durationMs * 1000L * 0.1).toLong().coerceAtMost(10_000_000L)
            val bitmap: Bitmap = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@withContext null
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
