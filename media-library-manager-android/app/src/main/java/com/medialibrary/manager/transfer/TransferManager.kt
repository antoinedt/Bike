package com.medialibrary.manager.transfer

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.medialibrary.manager.data.SettingsRepository
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.TransferTarget
import com.medialibrary.manager.torrent.StoredTorrent
import com.medialibrary.manager.torrent.TorrentEngine
import com.medialibrary.manager.torrent.TorrentSnapshot
import com.medialibrary.manager.torrent.TorrentState
import com.medialibrary.manager.torrent.TorrentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class CopyState { QUEUED, RUNNING, DONE, FAILED }

data class CopyJob(
    val id: String = UUID.randomUUID().toString(),
    val item: MediaItem,
    val target: TransferTarget,
    val targetLabel: String,
    val state: CopyState = CopyState.QUEUED,
    val copiedBytes: Long = 0,
    val totalBytes: Long = item.sizeBytes,
    /** Name it was saved under (may differ if the original was taken). */
    val savedAs: String? = null,
    val error: String? = null
)

/** A finished download being saved to its destination right now. */
data class Delivery(val torrentId: String, val copiedBytes: Long, val totalBytes: Long)

/**
 * Everything that keeps running when you leave the app: in-app torrent downloads, saving their
 * files to where they belong once finished, and "Copy to…" jobs. Runs in an app-wide scope (not a
 * screen's), and keeps [TransferService] in the foreground while anything is in progress so Android
 * doesn't kill it halfway.
 *
 * Torrents download into app storage first (libtorrent needs real file paths, which SAF folders and
 * SMB shares don't have), then get copied to the chosen destination and deleted from app storage.
 */
class TransferManager(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val storage: TransferStorage
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineLock = Mutex()
    private val engine = TorrentEngine(JsonTorrentStore(File(context.filesDir, "torrents.json"))) { finished ->
        scope.launch { deliver(finished.id) }
    }
    private var started = false

    private val _torrents = MutableStateFlow<List<TorrentSnapshot>>(emptyList())
    val torrents: StateFlow<List<TorrentSnapshot>> = _torrents.asStateFlow()

    private val _copies = MutableStateFlow<List<CopyJob>>(emptyList())
    val copies: StateFlow<List<CopyJob>> = _copies.asStateFlow()

    private val _deliveries = MutableStateFlow<Map<String, Delivery>>(emptyMap())
    val deliveries: StateFlow<Map<String, Delivery>> = _deliveries.asStateFlow()

    /** Fires with the place that just received new files, so the Library can rescan it. */
    private val _libraryChanged = MutableSharedFlow<TransferTarget>(extraBufferCapacity = 8)
    val libraryChanged: SharedFlow<TransferTarget> = _libraryChanged.asSharedFlow()

    private val copyQueue = Channel<String>(Channel.UNLIMITED)
    private val cancelledCopies = mutableSetOf<String>()
    private var ticker: Job? = null

    /** Restores saved downloads and resumes unfinished ones. Safe to call repeatedly. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            engineLock.withLock { engine.start() }
            refresh()
            // Finished but never saved to its destination (the app was closed while saving).
            engine.list().filter { it.state == TorrentState.DONE && it.delivery == null }.forEach { deliver(it.id) }
            ensureTicker()
        }
        scope.launch { for (jobId in copyQueue) runCopy(jobId) }
    }

    fun tempDir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "torrents").apply { mkdirs() }

    suspend fun addTorrent(source: Any): TorrentSnapshot = withContext(Dispatchers.IO) {
        start()
        val target = settingsRepo.current().torrentTarget
        val added = engineLock.withLock { engine.add(source, tempDir(), target.encode()) }
        refresh()
        ensureTicker()
        added
    }

    fun pause(id: String) = scope.launch { engineLock.withLock { engine.pause(id) }; refresh() }

    fun resume(id: String) = scope.launch {
        engineLock.withLock { engine.resume(id) }
        refresh()
        ensureTicker()
    }

    /** [deleteFiles] only matters before the files were saved to their destination. */
    fun remove(id: String, deleteFiles: Boolean) = scope.launch {
        engineLock.withLock { engine.remove(id, deleteFiles) }
        refresh()
    }

    fun retryDelivery(id: String) = scope.launch { deliver(id) }

    fun copy(item: MediaItem, target: TransferTarget) = scope.launch {
        start()
        val job = CopyJob(item = item, target = target, targetLabel = storage.label(target, settingsRepo.current()))
        _copies.update { it + job }
        copyQueue.send(job.id)
        updateService()
    }

    fun cancelCopy(jobId: String) {
        synchronized(cancelledCopies) { cancelledCopies += jobId }
        _copies.update { jobs -> jobs.map { if (it.id == jobId && it.state == CopyState.QUEUED) it.copy(state = CopyState.FAILED, error = "Cancelled.") else it } }
    }

    fun clearFinishedCopies() = _copies.update { jobs -> jobs.filter { it.state == CopyState.QUEUED || it.state == CopyState.RUNNING } }

    fun isBusy(): Boolean =
        engine.hasActive() || _deliveries.value.isNotEmpty() ||
            _copies.value.any { it.state == CopyState.QUEUED || it.state == CopyState.RUNNING }

    /** Starts the foreground service when work is running (called again when the app comes to the front). */
    fun updateService() {
        if (isBusy()) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java)) }
        } else {
            context.stopService(Intent(context, TransferService::class.java))
        }
    }

    private suspend fun runCopy(jobId: String) {
        val job = _copies.value.find { it.id == jobId } ?: return
        if (job.state != CopyState.QUEUED) return
        setCopy(jobId) { it.copy(state = CopyState.RUNNING) }
        updateService()
        val settings = settingsRepo.current()
        runCatching {
            val (input, size) = storage.openSource(job.item, settings)
            if (size > 0) setCopy(jobId) { it.copy(totalBytes = size) }
            input.use {
                storage.write(job.target, settings, job.item.name, it, size, { copied ->
                    setCopy(jobId) { j -> j.copy(copiedBytes = copied) }
                }, isCancelled = { synchronized(cancelledCopies) { jobId in cancelledCopies } })
            }
        }.onSuccess { savedAs ->
            setCopy(jobId) { it.copy(state = CopyState.DONE, savedAs = savedAs, copiedBytes = it.totalBytes) }
            _libraryChanged.tryEmit(job.target)
        }.onFailure { e ->
            setCopy(jobId) { it.copy(state = CopyState.FAILED, error = e.message ?: e.javaClass.simpleName) }
        }
        updateService()
    }

    private fun setCopy(jobId: String, change: (CopyJob) -> CopyJob) =
        _copies.update { jobs -> jobs.map { if (it.id == jobId) change(it) else it } }

    /** Copies a finished download's files to its destination, then deletes them from app storage. */
    private suspend fun deliver(id: String) {
        if (_deliveries.value.containsKey(id)) return
        val snapshot = engine.list().find { it.id == id } ?: return
        val source = engine.downloadedFile(id)
        if (source == null || !source.exists()) {
            engine.setDelivery(id, "The downloaded files are missing.")
            refresh()
            return
        }
        val target = TransferTarget.decode(snapshot.destination)
        val settings = settingsRepo.current()
        val files = if (source.isDirectory) source.walkTopDown().filter { it.isFile }.toList() else listOf(source)
        val total = files.sumOf { it.length() }
        _deliveries.update { it + (id to Delivery(id, 0, total)) }
        updateService()
        var done = 0L
        val result = runCatching {
            for (file in files) {
                // Keep the torrent's own folder ("Show Season 1/ep1.mkv"); a single file goes in as-is.
                val rel = file.relativeTo(source.parentFile!!).invariantSeparatorsPath
                file.inputStream().use { input ->
                    storage.write(target, settings, rel, input, file.length(), { copied ->
                        _deliveries.update { it + (id to Delivery(id, done + copied, total)) }
                    })
                }
                done += file.length()
            }
        }
        result.onSuccess {
            source.deleteRecursively()
            engine.setDelivery(id, "")
            _libraryChanged.tryEmit(target)
            Notifications.downloadSaved(context, snapshot.name, storage.label(target, settings))
        }.onFailure { e ->
            engine.setDelivery(id, "Couldn't save to ${storage.label(target, settings)}: ${e.message}")
        }
        _deliveries.update { it - id }
        refresh()
        updateService()
    }

    private fun refresh() {
        _torrents.value = runCatching { engine.list() }.getOrDefault(_torrents.value)
    }

    private fun ensureTicker() {
        updateService()
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var ticks = 0
            while (isActive) {
                refresh()
                if (++ticks % 30 == 0) runCatching { engine.checkpoint() }
                if (!engine.hasActive() && _deliveries.value.isEmpty()) {
                    updateService()
                    break
                }
                delay(1000)
            }
        }
    }
}

/** Saves the download list as JSON in app storage. */
private class JsonTorrentStore(private val file: File) : TorrentStore {
    override fun load(): List<StoredTorrent> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            StoredTorrent(
                id = o.getString("id"),
                name = o.getString("name"),
                savePath = o.getString("savePath"),
                magnet = o.optStringOrNull("magnet"),
                torrentFile = o.optStringOrNull("torrentFile"),
                resumeData = o.optStringOrNull("resumeData"),
                totalBytes = o.optLong("totalBytes"),
                doneBytes = o.optLong("doneBytes"),
                progress = o.optDouble("progress", 0.0).toFloat(),
                paused = o.optBoolean("paused"),
                done = o.optBoolean("done"),
                error = o.optStringOrNull("error"),
                addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                destination = o.optString("destination", ""),
                delivery = o.optStringOrNull("delivery")
            )
        }
    }.getOrDefault(emptyList())

    override fun save(entries: List<StoredTorrent>) {
        val array = JSONArray()
        for (e in entries) {
            array.put(
                JSONObject()
                    .put("id", e.id).put("name", e.name).put("savePath", e.savePath)
                    .put("magnet", e.magnet).put("torrentFile", e.torrentFile).put("resumeData", e.resumeData)
                    .put("totalBytes", e.totalBytes).put("doneBytes", e.doneBytes).put("progress", e.progress.toDouble())
                    .put("paused", e.paused).put("done", e.done).put("error", e.error)
                    .put("addedAt", e.addedAt).put("destination", e.destination).put("delivery", e.delivery)
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)
}
