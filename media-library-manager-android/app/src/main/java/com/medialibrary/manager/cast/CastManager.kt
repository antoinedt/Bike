package com.medialibrary.manager.cast

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.medialibrary.manager.data.SettingsRepository
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaKind
import com.medialibrary.manager.model.MediaSourceType
import com.medialibrary.manager.network.SmbContextFactory
import jcifs.smb.SmbFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A TV the phone can cast to. */
data class CastTarget(
    val id: String,
    val name: String,
    /** "chromecast" (Google Cast) or "dlna" (e.g. a Samsung TV). */
    val kind: String,
    val host: String,
    val port: Int = 8009,
    val controlUrl: String = ""
)

data class CastUiState(
    val deviceName: String,
    val itemName: String,
    /** loading / playing / paused / buffering / stopped / error */
    val state: String,
    val position: Double,
    val duration: Double,
    val error: String? = null
)

/**
 * Casting from the phone: finds Google TV / Chromecast devices (mDNS via NsdManager) and DLNA TVs
 * such as Samsung (SSDP), and plays library items on them. The TV streams the file from a small
 * server in the app (MediaHttpServer); TransferService keeps the app running while that happens.
 */
class CastManager(private val context: Context, private val settingsRepo: SettingsRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MediaHttpServer()
    private var player: CastPlayer? = null
    private var token: String? = null
    private var poll: Job? = null

    private val _state = MutableStateFlow<CastUiState?>(null)
    val state: StateFlow<CastUiState?> = _state.asStateFlow()

    /** The TV picked with the Cast button: tapping an item plays it there. */
    private val _target = MutableStateFlow<CastTarget?>(null)
    val target: StateFlow<CastTarget?> = _target.asStateFlow()

    fun isActive() = _state.value.let { it != null && it.state != "error" && it.state != "stopped" }

    fun selectTarget(target: CastTarget?) {
        _target.value = target
        if (target == null) scope.launch { stop() }
    }

    /** Google Cast and DLNA TVs on the Wi-Fi (takes ~3 s). */
    suspend fun discover(timeoutMs: Int = 3000): List<CastTarget> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        // Android drops multicast (SSDP / mDNS replies) unless an app holds this lock.
        val lock = wifi?.createMulticastLock("media-library-cast")?.apply { setReferenceCounted(false); acquire() }
        try {
            val cast = async { discoverCast(timeoutMs) }
            val dlna = async { runCatching { Dlna.discover(timeoutMs) }.getOrDefault(emptyList()) }
            (cast.await() + dlna.await().map { CastTarget("dlna:${it.id}", it.name, "dlna", it.host, controlUrl = it.controlUrl) })
                .sortedBy { it.name.lowercase() }
        } finally {
            runCatching { lock?.release() }
        }
    }

    private fun discoverCast(timeoutMs: Int): List<CastTarget> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val found = ConcurrentHashMap<String, NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                found[serviceInfo.serviceName] = serviceInfo
            }
        }
        runCatching { nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { return emptyList() }
        Thread.sleep(timeoutMs.toLong())
        runCatching { nsd.stopServiceDiscovery(listener) }
        // One resolve at a time: NsdManager refuses parallel ones on older Android versions.
        return found.values.mapNotNull { info -> resolve(nsd, info) }
    }

    @Suppress("DEPRECATION")
    private fun resolve(nsd: NsdManager, info: NsdServiceInfo): CastTarget? {
        val latch = CountDownLatch(1)
        var resolved: NsdServiceInfo? = null
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = latch.countDown()
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                resolved = serviceInfo
                latch.countDown()
            }
        })
        latch.await(3, TimeUnit.SECONDS)
        val r = resolved ?: return null
        val host = r.host?.hostAddress ?: return null
        val attrs = r.attributes
        val name = attrs["fn"]?.let { String(it) } ?: r.serviceName
        val id = attrs["id"]?.let { String(it) } ?: r.serviceName
        return CastTarget("cast:$id", name, "chromecast", host, r.port)
    }

    private fun sourceFor(item: MediaItem): () -> MediaSource {
        if (item.sourceType == MediaSourceType.NETWORK) {
            return {
                val share = kotlinx.coroutines.runBlocking { settingsRepo.shareById(item.shareId ?: "") }
                    ?: error("The network share for this item is no longer configured.")
                val url = SmbContextFactory.urlFor(share, item.path.removePrefix("smb://${share.id}/"))
                val ctx = SmbContextFactory.contextFor(share)
                val size = SmbFile(url, ctx).length()
                object : MediaSource {
                    override val size = size
                    override fun open(offset: Long): InputStream = SmbFile(url, ctx).openInputStream().also { skipFully(it, offset) }
                }
            }
        }
        return {
            val uri = Uri.parse(item.path)
            val size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()
                ?.takeIf { it > 0 } ?: item.sizeBytes
            object : MediaSource {
                override val size = size
                override fun open(offset: Long): InputStream =
                    (context.contentResolver.openInputStream(uri) ?: error("Can't open ${item.name}")).also { skipFully(it, offset) }
            }
        }
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                if (input.read() == -1) return
                left--
            } else {
                left -= skipped
            }
        }
    }

    fun cast(item: MediaItem) {
        val target = _target.value ?: return
        scope.launch {
            stop(keepTarget = true)
            val title = item.title ?: item.name
            _state.value = CastUiState(target.name, title, "loading", 0.0, 0.0)
            try {
                val contentType = MediaHttpServer.contentTypeFor(item.extension)
                val published = server.publish(sourceFor(item), item.name, contentType, target.host)
                token = published.token
                val p: CastPlayer = if (target.kind == "chromecast") {
                    CastV2Session.connect(target.host, target.port).also { it.load(published.url, contentType, title) }
                } else {
                    val kind = when (item.kind) { MediaKind.AUDIO -> "audio"; MediaKind.IMAGE -> "image"; else -> "video" }
                    DlnaPlayer(DlnaDevice(target.id, target.name, target.host, target.controlUrl)).also { it.load(published.url, contentType, title, kind) }
                }
                player = p
                com.medialibrary.manager.transfer.TransferService.refresh(context)
                poll = scope.launch {
                    while (isActive) {
                        delay(1000)
                        val s = p.status() ?: continue
                        val current = _state.value ?: break
                        if (s.state == "stopped" && current.state == "loading") continue
                        _state.value = current.copy(state = s.state, position = s.position, duration = if (s.duration > 0) s.duration else current.duration)
                        if (s.state == "stopped") {
                            release()
                            com.medialibrary.manager.transfer.TransferService.refresh(context)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                release()
                _state.value = CastUiState(target.name, title, "error", 0.0, 0.0, e.message ?: "Couldn't start casting.")
            }
        }
    }

    fun control(action: String, value: Double = 0.0) {
        val p = player ?: return
        scope.launch {
            runCatching {
                when (action) {
                    "play" -> p.play()
                    "pause" -> p.pause()
                    "seek" -> p.seek(value.coerceAtLeast(0.0))
                }
            }
            if (action == "seek") _state.value = _state.value?.copy(position = value.coerceAtLeast(0.0))
        }
    }

    fun stopCasting() {
        scope.launch { stop(keepTarget = true) }
    }

    private suspend fun stop(keepTarget: Boolean = false) = withContext(Dispatchers.IO) {
        poll?.cancel()
        poll = null
        player?.let { runCatching { it.stop() } }
        release()
        _state.value = null
        if (!keepTarget) _target.value = null
        com.medialibrary.manager.transfer.TransferService.refresh(context)
    }

    private fun release() {
        player = null
        token?.let(server::unpublish)
        token = null
    }
}
