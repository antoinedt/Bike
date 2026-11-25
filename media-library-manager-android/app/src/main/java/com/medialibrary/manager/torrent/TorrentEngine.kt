package com.medialibrary.manager.torrent

import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.InfoHash
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.FileErrorAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.session_handle
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Plain Kotlin on purpose (no Android imports): the download logic is tested on a desktop JVM
// against a local seeder. Android-specific parts (storage, the foreground service, moving finished
// files to their destination) live in TransferManager.

enum class TorrentState { METADATA, CHECKING, DOWNLOADING, PAUSED, DONE, ERROR }

/** What the UI shows for one download. */
data class TorrentSnapshot(
    val id: String,
    val name: String,
    val state: TorrentState,
    /** 0f..1f */
    val progress: Float,
    val downloadRate: Long,
    val uploadRate: Long,
    val peers: Int,
    val totalBytes: Long,
    val doneBytes: Long,
    val etaSeconds: Long?,
    /** Where libtorrent writes it (app storage on Android). */
    val savePath: String,
    val error: String?,
    val addedAt: Long,
    /** Opaque to the engine: where the app should put the finished files. */
    val destination: String,
    /** Opaque to the engine: null = not delivered yet, "" = delivered, anything else = delivery error. */
    val delivery: String?
)

/** One download as saved between app runs. */
data class StoredTorrent(
    val id: String,
    var name: String,
    val savePath: String,
    val magnet: String? = null,
    /** Base64 .torrent file, when added from one. */
    val torrentFile: String? = null,
    /** Base64 libtorrent resume data (includes the file list and which pieces are done). */
    var resumeData: String? = null,
    var totalBytes: Long = 0,
    var doneBytes: Long = 0,
    var progress: Float = 0f,
    var paused: Boolean = false,
    var done: Boolean = false,
    var error: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val destination: String = "",
    var delivery: String? = null
)

interface TorrentStore {
    fun load(): List<StoredTorrent>
    fun save(entries: List<StoredTorrent>)
}

/**
 * In-app BitTorrent downloads on libtorrent (via libtorrent4j). Same behaviour as the desktop app:
 * a download stops sharing as soon as it's complete, a paused download is taken out of the session
 * entirely (so nothing can quietly restart it), and unfinished downloads resume after a restart
 * from libtorrent's resume data, without re-checking what's already on disk.
 */
class TorrentEngine(
    private val store: TorrentStore,
    /** Tests turn off DHT/LSD/port mapping; the app uses libtorrent's defaults. */
    private val settings: SettingsPack? = null,
    private val onFinished: (TorrentSnapshot) -> Unit = {}
) {
    private val session = SessionManager(false)
    private val lock = Any()
    private val entries = LinkedHashMap<String, StoredTorrent>()
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val resumeWaiters = ConcurrentHashMap<String, CountDownLatch>()
    @Volatile private var started = false

    private val listener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.TORRENT_FINISHED.swig(),
            AlertType.TORRENT_ERROR.swig(),
            AlertType.FILE_ERROR.swig(),
            AlertType.SAVE_RESUME_DATA.swig(),
            AlertType.SAVE_RESUME_DATA_FAILED.swig()
        )

        override fun alert(alert: Alert<*>) {
            runCatching { handleAlert(alert) }
        }
    }

    fun start() {
        if (started) return
        synchronized(lock) {
            if (started) return
            session.addListener(listener)
            session.start(settings?.let { SessionParams(it) } ?: SessionParams())
            store.load().forEach { entries[it.id] = it }
            started = true
            for (entry in entries.values) {
                if (!entry.done && !entry.paused) {
                    runCatching { startHandle(entry) }.onFailure { entry.error = it.message ?: "Couldn't restart." }
                }
            }
            persist()
        }
    }

    /**
     * Starts downloading a magnet link, an info hash, an http(s) link to a .torrent file (or one
     * that redirects to a magnet link), or the bytes of a .torrent file. Adding something that's
     * already in the list resumes it if it was paused or failed.
     */
    fun add(source: Any, savePath: File, destination: String): TorrentSnapshot {
        start()
        val resolved = resolveSource(source)
        val ec = error_code()
        val params: add_torrent_params = when (resolved) {
            is String -> libtorrent.parse_magnet_uri(resolved, ec)
            is ByteArray -> add_torrent_params.load_torrent_buffer(Vectors.bytes2byte_vector(resolved), ec)
            else -> error("unreachable")
        }
        if (ec.failed()) throw IllegalArgumentException(INVALID)
        val id = idOf(InfoHash(params.info_hashes))
        val name = params.ti_ptr()?.takeIf { it.is_valid }?.name()?.takeIf { it.isNotBlank() }
            ?: AddTorrentParams(params).name?.takeIf { it.isNotBlank() }
            ?: id

        synchronized(lock) {
            entries[id]?.let { existing ->
                if (existing.paused || existing.error != null) resume(id)
                return snapshot(existing)
            }
            savePath.mkdirs()
            val entry = StoredTorrent(
                id = id,
                name = name,
                savePath = savePath.absolutePath,
                magnet = resolved as? String,
                torrentFile = (resolved as? ByteArray)?.let { Base64.getEncoder().encodeToString(it) },
                totalBytes = params.ti_ptr()?.takeIf { it.is_valid }?.total_size() ?: 0,
                destination = destination
            )
            entries[id] = entry
            startHandle(entry, params)
            persist()
            return snapshot(entry)
        }
    }

    fun pause(id: String) {
        val entry = synchronized(lock) { entries[id] } ?: return
        if (entry.done || entry.paused) return
        val handle = handles[id]
        if (handle != null) {
            refresh(entry, handle)
            saveResumeAndWait(id, handle)
            handles.remove(id)
            session.remove(handle)
        }
        synchronized(lock) {
            entry.paused = true
            persist()
        }
    }

    fun resume(id: String) {
        synchronized(lock) {
            val entry = entries[id] ?: return
            if (entry.done) return
            entry.paused = false
            entry.error = null
            handles.remove(id)?.let { session.remove(it) }
            runCatching { startHandle(entry) }.onFailure { entry.error = it.message ?: "Couldn't restart." }
            persist()
        }
    }

    /** With [deleteFiles], also deletes what's in [StoredTorrent.savePath] for this download. */
    fun remove(id: String, deleteFiles: Boolean) {
        val entry = synchronized(lock) { entries.remove(id) } ?: return
        handles.remove(id)?.let { session.remove(it) }
        if (deleteFiles) downloadedFile(entry)?.deleteRecursively()
        synchronized(lock) { persist() }
    }

    /** The downloaded file or folder inside its save path (null if the name would escape it). */
    fun downloadedFile(id: String): File? = synchronized(lock) { entries[id] }?.let(::downloadedFile)

    private fun downloadedFile(entry: StoredTorrent): File? {
        val root = File(entry.savePath).canonicalFile
        val target = File(root, entry.name).canonicalFile
        return target.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    /** For the app layer: records where the finished files went (see [StoredTorrent.delivery]). */
    fun setDelivery(id: String, delivery: String?) = synchronized(lock) {
        entries[id]?.let {
            it.delivery = delivery
            persist()
        }
    }

    fun list(): List<TorrentSnapshot> {
        val current = synchronized(lock) { entries.values.toList() }
        return current.sortedByDescending { it.addedAt }.map { entry ->
            handles[entry.id]?.let { refresh(entry, it) }
            snapshot(entry)
        }
    }

    fun hasActive(): Boolean = synchronized(lock) { entries.values.any { !it.done && !it.paused && it.error == null } }

    /** Saves resume data for running downloads that need it, so a crash loses at most this interval. */
    fun checkpoint() {
        for ((id, handle) in handles) {
            if (runCatching { handle.needSaveResumeData() }.getOrDefault(false)) {
                handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
            }
        }
    }

    fun shutdown() {
        if (!started) return
        for ((id, handle) in handles.toList()) saveResumeAndWait(id, handle)
        synchronized(lock) {
            for ((id, handle) in handles) entries[id]?.let { refresh(it, handle) }
            persist()
            started = false
        }
        handles.clear()
        session.removeListener(listener)
        session.stop()
    }

    /** Points a download at a peer directly (tests, and anyone who knows a peer's address). */
    fun connectPeer(id: String, host: String, port: Int) {
        handles[id]?.swig()?.connect_peer(TcpEndpoint(host, port).swig())
    }

    private fun startHandle(entry: StoredTorrent, fresh: add_torrent_params? = null) {
        val ec = error_code()
        val params = fresh
            ?: entry.resumeData?.let { data ->
                libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(Base64.getDecoder().decode(data)), ec)
                    .takeIf { !ec.failed() }
            }
            ?: entry.torrentFile?.let { add_torrent_params.load_torrent_buffer(Vectors.bytes2byte_vector(Base64.getDecoder().decode(it)), ec) }
            ?: libtorrent.parse_magnet_uri(entry.magnet ?: "", ec)
        if (ec.failed()) throw IOException("Couldn't restart this download: ${ec.message()}")
        params.save_path = entry.savePath
        val wrapper = AddTorrentParams(params)
        wrapper.flags = wrapper.flags.and_(TorrentFlags.PAUSED.inv()).or_(TorrentFlags.AUTO_MANAGED)
        val handle = session.swig().add_torrent(params, ec)
        if (ec.failed()) throw IOException(ec.message())
        handles[entry.id] = TorrentHandle(handle)
    }

    private fun handleAlert(alert: Alert<*>) {
        val torrentAlert = alert as? TorrentAlert<*> ?: return
        val handle = torrentAlert.handle()
        val id = idOf(InfoHash(handle.swig().info_hashes()))
        when (alert.type()) {
            AlertType.METADATA_RECEIVED -> {
                synchronized(lock) {
                    entries[id]?.let { refresh(it, handle) }
                    persist()
                }
                // Keep the file list, so a restart doesn't need peers just to find out what to download.
                handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
            }
            AlertType.TORRENT_FINISHED -> {
                val entry = synchronized(lock) { entries[id] } ?: return
                if (entry.done) return
                refresh(entry, handle)
                if (entry.totalBytes > 0 && entry.doneBytes < entry.totalBytes) return
                synchronized(lock) {
                    entry.done = true
                    entry.progress = 1f
                    entry.doneBytes = entry.totalBytes
                    entry.error = null
                    persist()
                }
                // No seeding once complete: take it out of the session, files stay where they are.
                // (Removing from inside the alert callback is fine; libtorrent queues it.)
                handles.remove(id)
                session.remove(handle)
                onFinished(snapshot(entry))
            }
            AlertType.TORRENT_ERROR, AlertType.FILE_ERROR -> {
                val message = when (alert) {
                    is TorrentErrorAlert -> alert.error().message
                    is FileErrorAlert -> alert.error().message
                    else -> null
                } ?: "Download error"
                synchronized(lock) {
                    entries[id]?.let {
                        it.error = message
                        persist()
                    }
                }
            }
            AlertType.SAVE_RESUME_DATA -> {
                val params = (alert as SaveResumeDataAlert).params()
                val bytes = AddTorrentParams.writeResumeDataBuf(params)
                synchronized(lock) {
                    entries[id]?.let {
                        it.resumeData = Base64.getEncoder().encodeToString(bytes)
                        persist()
                    }
                }
                resumeWaiters.remove(id)?.countDown()
            }
            AlertType.SAVE_RESUME_DATA_FAILED -> resumeWaiters.remove(id)?.countDown()
            else -> Unit
        }
    }

    private fun saveResumeAndWait(id: String, handle: TorrentHandle) {
        val latch = CountDownLatch(1)
        resumeWaiters[id] = latch
        runCatching { handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }.onFailure { resumeWaiters.remove(id) }
        latch.await(5, TimeUnit.SECONDS)
    }

    private fun refresh(entry: StoredTorrent, handle: TorrentHandle) {
        val status = runCatching { handle.status() }.getOrNull() ?: return
        if (status.hasMetadata()) {
            status.name()?.takeIf { it.isNotBlank() }?.let { entry.name = it }
            entry.totalBytes = status.totalWanted()
            entry.doneBytes = status.totalWantedDone()
        }
        entry.progress = status.progress()
        liveStatus[entry.id] = status
    }

    private val liveStatus = ConcurrentHashMap<String, TorrentStatus>()

    private fun snapshot(entry: StoredTorrent): TorrentSnapshot {
        val live = if (handles.containsKey(entry.id)) liveStatus[entry.id] else null
        val state = when {
            entry.done -> TorrentState.DONE
            entry.error != null -> TorrentState.ERROR
            entry.paused || live == null && !handles.containsKey(entry.id) -> TorrentState.PAUSED
            live == null -> TorrentState.METADATA
            live.state() == TorrentStatus.State.DOWNLOADING_METADATA -> TorrentState.METADATA
            live.state() == TorrentStatus.State.CHECKING_FILES || live.state() == TorrentStatus.State.CHECKING_RESUME_DATA -> TorrentState.CHECKING
            else -> TorrentState.DOWNLOADING
        }
        val running = state == TorrentState.DOWNLOADING || state == TorrentState.METADATA || state == TorrentState.CHECKING
        val rate = if (running) live?.downloadPayloadRate()?.toLong() ?: 0 else 0
        val remaining = entry.totalBytes - entry.doneBytes
        return TorrentSnapshot(
            id = entry.id,
            name = entry.name,
            state = state,
            progress = if (entry.done) 1f else entry.progress,
            downloadRate = rate,
            uploadRate = if (running) live?.uploadPayloadRate()?.toLong() ?: 0 else 0,
            peers = if (running) live?.numPeers() ?: 0 else 0,
            totalBytes = entry.totalBytes,
            doneBytes = entry.doneBytes,
            etaSeconds = if (rate > 0 && entry.totalBytes > 0) remaining / rate else null,
            savePath = entry.savePath,
            error = entry.error,
            addedAt = entry.addedAt,
            destination = entry.destination,
            delivery = entry.delivery
        )
    }

    private fun persist() = store.save(entries.values.map { it.copy() })

    companion object {
        const val INVALID = "That isn't a valid magnet link or .torrent file."
        const val UNSUPPORTED = "Paste a magnet link, an info hash, or an http(s) link to a .torrent file."
        private const val MAX_TORRENT_BYTES = 10 * 1024 * 1024

        /** The id a download is known by: its v1 info hash in hex (or the v2 one, for v2-only torrents). */
        fun idOf(hashes: InfoHash): String = (if (hashes.hasV1()) hashes.v1 else hashes.best).toHex()

        /** Turns what the user pasted into a magnet link (String) or .torrent bytes (ByteArray). */
        fun resolveSource(source: Any): Any {
            if (source is ByteArray) return source
            val text = (source as? String)?.trim() ?: throw IllegalArgumentException(UNSUPPORTED)
            if (text.startsWith("magnet:", ignoreCase = true)) return text
            if (Regex("^[0-9a-fA-F]{40}$").matches(text) || Regex("^[A-Za-z2-7]{32}$").matches(text)) {
                return "magnet:?xt=urn:btih:$text"
            }
            val uri = runCatching { URI(text) }.getOrNull()
            if (uri?.scheme?.lowercase() !in setOf("http", "https")) throw IllegalArgumentException(UNSUPPORTED)
            return fetchTorrent(text)
        }

        private fun fetchTorrent(start: String): Any {
            var url = start
            repeat(6) {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                try {
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location") ?: throw IOException("Couldn't download the .torrent file (HTTP $code).")
                        if (location.startsWith("magnet:", ignoreCase = true)) return location
                        url = URL(URL(url), location).toString()
                        if (!url.startsWith("http://") && !url.startsWith("https://")) throw IllegalArgumentException(UNSUPPORTED)
                        return@repeat
                    }
                    if (code !in 200..299) throw IOException("Couldn't download the .torrent file (HTTP $code).")
                    connection.inputStream.use { input ->
                        // (Not readNBytes: that's Android 13+.)
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            if (out.size() > MAX_TORRENT_BYTES) throw IOException("That .torrent file is too big.")
                        }
                        return out.toByteArray()
                    }
                } finally {
                    connection.disconnect()
                }
            }
            throw IOException("Too many redirects while downloading the .torrent file.")
        }
    }
}
