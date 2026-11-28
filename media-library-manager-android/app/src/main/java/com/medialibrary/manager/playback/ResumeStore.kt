package com.medialibrary.manager.playback

import android.content.Context
import com.medialibrary.manager.di.ServiceLocator
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaSourceType
import com.medialibrary.manager.model.NetworkShare
import com.medialibrary.manager.network.SmbSidecar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where each item was left off: kept on this device, and — for items on a share — in the file's
 * shared ".metadata.json" sidecar too, so the PC, the TV or another phone can continue it.
 * Whichever was saved last wins. Not kept for the first 30 s or the last 5% (or once it ended).
 * Also feeds the Library's "Continue watching" row.
 */
class ResumeStore(context: Context) {
    data class Entry(val itemId: String, val positionMs: Long, val durationMs: Long, val at: Long)

    private val prefs = context.applicationContext.getSharedPreferences("resume_positions", Context.MODE_PRIVATE)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun shareFor(item: MediaItem): Pair<NetworkShare, String>? {
        if (item.sourceType != MediaSourceType.NETWORK) return null
        val share = item.shareId?.let { ServiceLocator.settingsRepository.shareById(it) } ?: return null
        return share to item.path.removePrefix("smb://${share.id}/")
    }

    /** The newest saved position, here or from another device; null = start from the beginning. */
    suspend fun load(item: MediaItem): Long? {
        val local = prefs.getLong(item.id, -1L).takeIf { it > 0 }?.let { it to prefs.getLong("${item.id}@at", 0L) }
        val shared = shareFor(item)?.let { (share, rel) -> SmbSidecar.read(share, rel) }
            ?.let { m -> m.resumePositionMs?.let { it to (m.resumeUpdatedAt ?: 0L) } }
        return listOfNotNull(local, shared).maxByOrNull { it.second }?.first
    }

    /** Saves here right away; to the shared file too when [share] is true (every ~15 s and on close). */
    fun save(item: MediaItem, position: Long, duration: Long, ended: Boolean = false, share: Boolean = false) {
        val now = System.currentTimeMillis()
        val keep = !ended && position >= 30_000 && !(duration > 0 && position >= duration * 0.95)
        if (keep) {
            prefs.edit().putLong(item.id, position).putLong("${item.id}@at", now)
                .putLong("${item.id}@dur", duration.coerceAtLeast(0)).apply()
        } else {
            prefs.edit().remove(item.id).remove("${item.id}@at").remove("${item.id}@dur").apply()
        }
        if (share) background.launch {
            runCatching { shareFor(item)?.let { (s, rel) -> SmbSidecar.writeResume(s, rel, if (keep) position else null, now) } }
        }
    }

    fun clear(item: MediaItem) = save(item, 0, 0, ended = true, share = true)

    /** Everything started and not finished on this device, newest first. */
    fun list(): List<Entry> = prefs.all.keys
        .filter { '@' !in it }
        .mapNotNull { id ->
            val pos = prefs.getLong(id, -1L).takeIf { it > 0 } ?: return@mapNotNull null
            Entry(id, pos, prefs.getLong("$id@dur", 0L), prefs.getLong("$id@at", 0L))
        }
        .sortedByDescending { it.at }
}
