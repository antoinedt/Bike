package com.medialibrary.manager.remote

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import com.medialibrary.manager.data.SettingsRepository
import com.medialibrary.manager.model.AppSettings
import com.medialibrary.manager.torrent.TorrentEngine
import com.medialibrary.manager.transfer.Notifications
import com.medialibrary.manager.transfer.TransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * "Send to another device", Android side: while the app is running it checks the mailbox share
 * every few seconds — starts downloads other devices sent here, and passes on their replies to
 * downloads sent from here — and says "I'm here" once a minute so other devices list it.
 */
class RemoteManager(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val transfers: TransferManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var started = false
    private var lastHeartbeat = 0L

    /** "Started X on TV", "Started X, sent from Phone" — shown as a snackbar. */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val platform: String by lazy {
        val tv = context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        if (tv) "android-tv" else "android"
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            settingsRepo.ensureDeviceId()
            while (true) {
                runCatching { tick() }
                delay(POLL_MS)
            }
        }
    }

    /** Call after changing the name or share, so other devices see the change right away. */
    fun refreshNow() {
        lastHeartbeat = 0
        scope.launch { runCatching { tick() } }
    }

    private suspend fun tick() = lock.withLock {
        withQueue { queue ->
            if (System.currentTimeMillis() - lastHeartbeat > HEARTBEAT_MS) {
                queue.heartbeat()
                lastHeartbeat = System.currentTimeMillis()
            }
            queue.poll()
        }
    }

    suspend fun devices(): List<RemoteDevice> = withContext(Dispatchers.IO) {
        withQueue { it.devices() } ?: throw IllegalStateException(NOT_SET_UP)
    }

    /**
     * Fetches a .torrent link here first (this device may have access to the site the other one
     * doesn't), then puts it in the other device's inbox. Returns the name it was sent as.
     */
    suspend fun send(deviceId: String, source: String, title: String?): String = withContext(Dispatchers.IO) {
        val resolved = TorrentEngine.resolveSource(source)
        val magnet = resolved as? String
        val name = title?.takeIf { it.isNotBlank() } ?: magnet?.let { RemoteQueue.magnetName(it) } ?: "Download"
        val job = withQueue { it.send(deviceId, magnet, resolved as? ByteArray, name) } ?: throw IllegalStateException(NOT_SET_UP)
        job.name
    }

    private suspend fun <T> withQueue(block: (RemoteQueue) -> T): T? {
        val settings = settingsRepo.current()
        val share = settings.networkShares.find { it.id == settings.remoteShareId } ?: return null
        val queue = RemoteQueue(SmbMailbox(share), me(settings), onJob = { job ->
            val added = runBlocking {
                if (job.magnet != null) transfers.addTorrent(job.magnet)
                else transfers.addTorrent(java.util.Base64.getDecoder().decode(job.torrent))
            }
            val text = "Started \"${added.name}\" (sent from ${job.fromName})."
            _events.tryEmit(text)
            Notifications.remote(context, "Download started", text)
            JobOutcome(true, "Downloading", added.name)
        }, onResult = { result ->
            val text = if (result.ok) "${result.toName} started downloading \"${result.name}\"."
            else "${result.toName} couldn't start \"${result.name}\": ${result.message}"
            _events.tryEmit(text)
            Notifications.remote(context, if (result.ok) "Started on ${result.toName}" else "Not started", text)
        })
        return block(queue)
    }

    private fun me(settings: AppSettings) = Me(settings.deviceId, deviceName(settings), platform)

    fun deviceName(settings: AppSettings): String = settings.deviceName.ifBlank { Build.MODEL ?: "Android device" }

    companion object {
        private const val POLL_MS = 8_000L
        private const val HEARTBEAT_MS = 60_000L
        const val NOT_SET_UP = "Pick a network share under Settings → Send to another device first."
    }
}
