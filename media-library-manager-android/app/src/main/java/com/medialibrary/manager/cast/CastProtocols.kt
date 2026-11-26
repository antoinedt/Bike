package com.medialibrary.manager.cast

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

// Casting protocols, in plain Kotlin (org.json is the only non-JDK piece; Android ships it) so
// they're tested on a desktop JVM. Same behaviour as the desktop app's castv2.ts / dlna.ts.

/** What the remote-control bar shows. */
data class CastPlayback(val state: String, val position: Double, val duration: Double)

/** A live Fizz channel to cast: the stream URL and the DRM details the Fizz receiver needs. */
data class CastLiveStream(
    val url: String,
    /** The content type to send in the LOAD (Fizz sends video/mp4; the receiver picks the parser). */
    val contentType: String,
    /** 2 = DASH, 4 = HLS. */
    val mediaFormatId: Int,
    val drmToken: String?,
    val drmLicenseUrl: String,
    val drmType: Int,
    val drmContentId: String?
)

interface CastPlayer {
    fun play()
    fun pause()
    fun seek(seconds: Double)
    /** Current state, or null if it couldn't be read this time. */
    fun status(): CastPlayback?
    fun stop()
}

// ---------------------------------------------------------------- Google Cast (CASTV2)

private const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
/** Fizz's own registered Cast receiver: it does the Widevine playback for a live channel on the TV,
 *  given the stream URL and the DRM token/type/content-id in the media metadata (as the Fizz app does). */
private const val FIZZ_RECEIVER = "559B0340"
private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
private const val SENDER = "sender-mlm"

object CastMessages {
    private fun varint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        do {
            var b = v and 0x7f
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.write(b)
        } while (v != 0)
    }

    private fun string(out: ByteArrayOutputStream, tag: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write(tag)
        varint(out, bytes.size)
        out.write(bytes)
    }

    /** CastMessage { protocol_version=1: 0, source_id=2, destination_id=3, namespace=4, payload_type=5: STRING, payload_utf8=6 }, length-prefixed. */
    fun encode(source: String, destination: String, namespace: String, data: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0x08); body.write(0x00)
        string(body, 0x12, source)
        string(body, 0x1a, destination)
        string(body, 0x22, namespace)
        body.write(0x28); body.write(0x00)
        string(body, 0x32, data)
        val bytes = body.toByteArray()
        val framed = ByteArrayOutputStream()
        framed.write(bytes.size ushr 24); framed.write(bytes.size ushr 16 and 0xff); framed.write(bytes.size ushr 8 and 0xff); framed.write(bytes.size and 0xff)
        framed.write(bytes)
        return framed.toByteArray()
    }

    /** Returns (source, destination, namespace, data). */
    fun decode(body: ByteArray): List<String> {
        val fields = arrayOf("", "", "", "")
        var pos = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = body[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        while (pos < body.size) {
            val key = readVarint().toInt()
            val field = key ushr 3
            when (key and 7) {
                0 -> readVarint()
                2 -> {
                    val len = readVarint().toInt()
                    val value = String(body, pos, len, Charsets.UTF_8)
                    pos += len
                    when (field) {
                        2 -> fields[0] = value
                        3 -> fields[1] = value
                        4 -> fields[2] = value
                        6 -> fields[3] = value
                    }
                }
                else -> error("Unexpected Cast message encoding.")
            }
        }
        return fields.toList()
    }
}

/** One connection to a Google TV / Chromecast, playing a URL on its Default Media Receiver. */
class CastV2Session private constructor(private val socket: SSLSocket) : CastPlayer {
    private val out: OutputStream = socket.outputStream
    private val requestIds = AtomicInteger(1)
    private val waiters = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    @Volatile private var transportId: String? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var mediaSessionId: Int? = null
    @Volatile private var last: CastPlayback? = null
    @Volatile private var closed = false

    companion object {
        fun connect(host: String, port: Int = 8009, timeoutMs: Int = 8000): CastV2Session {
            // Cast devices present a self-signed certificate.
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            val ssl = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
            val socket = ssl.socketFactory.createSocket() as SSLSocket
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.startHandshake()
            return CastV2Session(socket).also { it.begin() }
        }
    }

    private fun begin() {
        thread(isDaemon = true, name = "cast-reader") { readLoop() }
        send(NS_CONNECTION, "receiver-0", JSONObject().put("type", "CONNECT"))
        thread(isDaemon = true, name = "cast-heartbeat") {
            while (!closed) {
                Thread.sleep(5000)
                send(NS_HEARTBEAT, "receiver-0", JSONObject().put("type", "PING"))
            }
        }
    }

    @Synchronized
    private fun send(namespace: String, destination: String, payload: JSONObject) {
        if (closed) return
        runCatching { out.write(CastMessages.encode(SENDER, destination, namespace, payload.toString())); out.flush() }
            .onFailure { close() }
    }

    private fun request(namespace: String, destination: String, payload: JSONObject, timeoutMs: Long = 15_000): JSONObject {
        val id = requestIds.getAndIncrement()
        val latch = CountDownLatch(1)
        var reply: JSONObject? = null
        waiters[id] = { reply = it; latch.countDown() }
        send(namespace, destination, payload.put("requestId", id))
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            waiters.remove(id)
            throw IllegalStateException("The TV didn't answer.")
        }
        return reply!!
    }

    private fun readLoop() {
        val input = DataInputStream(socket.inputStream)
        try {
            while (!closed) {
                val len = input.readInt()
                val body = ByteArray(len)
                input.readFully(body)
                val (source, _, namespace, data) = CastMessages.decode(body)
                val payload = runCatching { JSONObject(data) }.getOrNull() ?: continue
                if (namespace == NS_HEARTBEAT && payload.optString("type") == "PING") {
                    send(NS_HEARTBEAT, source, JSONObject().put("type", "PONG"))
                    continue
                }
                if (namespace == NS_MEDIA && payload.optString("type") == "MEDIA_STATUS") {
                    val status = payload.optJSONArray("status")?.optJSONObject(0)
                    if (status != null) {
                        if (status.has("mediaSessionId")) mediaSessionId = status.getInt("mediaSessionId")
                        val duration = status.optJSONObject("media")?.optDouble("duration", Double.NaN)?.takeIf { !it.isNaN() }
                        last = CastPlayback(
                            when (status.optString("playerState")) {
                                "PLAYING" -> "playing"; "PAUSED" -> "paused"; "BUFFERING" -> "buffering"; else -> "stopped"
                            },
                            status.optDouble("currentTime", 0.0),
                            duration ?: last?.duration ?: 0.0
                        )
                    }
                }
                if (namespace == NS_CONNECTION && payload.optString("type") == "CLOSE" && source == transportId) {
                    last = CastPlayback("stopped", last?.position ?: 0.0, last?.duration ?: 0.0)
                }
                val id = payload.optInt("requestId", 0)
                if (id != 0) waiters.remove(id)?.invoke(payload)
            }
        } catch (_: Exception) {
            close()
        }
    }

    /** Launches [appId] on the TV (or joins it) and connects to its media transport. */
    private fun launchApp(appId: String) {
        val status = request(NS_RECEIVER, "receiver-0", JSONObject().put("type", "LAUNCH").put("appId", appId))
        if (status.optString("type") == "LAUNCH_ERROR") error("The TV couldn't start its player.")
        val apps: JSONArray = status.optJSONObject("status")?.optJSONArray("applications") ?: JSONArray()
        val app = (0 until apps.length()).map { apps.getJSONObject(it) }.firstOrNull { it.optString("appId") == appId }
            ?: error("The TV didn't start its player.")
        transportId = app.getString("transportId")
        sessionId = app.optString("sessionId")
        send(NS_CONNECTION, transportId!!, JSONObject().put("type", "CONNECT"))
    }

    private fun sendLoad(media: JSONObject, failMessage: String) {
        val reply = request(NS_MEDIA, transportId!!, JSONObject().put("type", "LOAD").put("autoplay", true).put("currentTime", 0).put("media", media))
        if (reply.optString("type") == "LOAD_FAILED" || reply.optString("type") == "INVALID_REQUEST") error(failMessage)
    }

    /** Starts the Default Media Receiver (or joins it) and plays [url]. */
    fun load(url: String, contentType: String, title: String) {
        launchApp(DEFAULT_MEDIA_RECEIVER)
        val media = JSONObject().put("contentId", url).put("contentType", contentType).put("streamType", "BUFFERED")
            .put("metadata", JSONObject().put("metadataType", 0).put("title", title))
        sendLoad(media, "The TV couldn't play this file (it may not support this format).")
    }

    /**
     * Casts a live Fizz channel. Launches Fizz's own receiver (which does the Widevine playback on
     * the TV) and hands it the stream URL plus the DRM token/type/content-id in the media metadata —
     * the same keys the Fizz app sends. A protected channel needs this receiver; the Default Media
     * Receiver can't do the Vualto Widevine licence.
     */
    fun loadLive(stream: CastLiveStream, title: String) {
        launchApp(FIZZ_RECEIVER)
        val metadata = JSONObject().put("metadataType", 1)
            .put("title", title).put("key_title", title).put("channel_name", title)
            .put("content_type", "4").put("media_format_id", stream.mediaFormatId)
        stream.drmToken?.let { token ->
            metadata.put("drm_token", token).put("drm_license_url", stream.drmLicenseUrl).put("drm_type", stream.drmType)
            stream.drmContentId?.let { metadata.put("contentId", it) }
        }
        val media = JSONObject().put("contentId", stream.url).put("contentUrl", stream.url)
            .put("contentType", stream.contentType).put("streamType", "LIVE").put("metadata", metadata)
        sendLoad(media, "The TV couldn't start the channel.")
    }

    private fun media(type: String, extra: JSONObject = JSONObject()) {
        val transport = transportId ?: return
        val session = mediaSessionId ?: return
        thread(isDaemon = true) { runCatching { request(NS_MEDIA, transport, extra.put("type", type).put("mediaSessionId", session)) } }
    }

    override fun play() = media("PLAY")
    override fun pause() = media("PAUSE")
    override fun seek(seconds: Double) = media("SEEK", JSONObject().put("currentTime", seconds))

    override fun status(): CastPlayback? {
        transportId?.let { runCatching { request(NS_MEDIA, it, JSONObject().put("type", "GET_STATUS"), 5000) } }
        return if (closed) CastPlayback("stopped", last?.position ?: 0.0, last?.duration ?: 0.0) else last
    }

    override fun stop() {
        sessionId?.let { s -> runCatching { request(NS_RECEIVER, "receiver-0", JSONObject().put("type", "STOP").put("sessionId", s), 3000) } }
        close()
    }

    private fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }
}

// ---------------------------------------------------------------- DLNA (UPnP AV)

data class DlnaDevice(val id: String, val name: String, val host: String, val controlUrl: String)

object Dlna {
    private const val AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    /** The protocolInfo DLNA flags TVs expect for a seekable HTTP stream. */
    const val FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    private fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name[^>]*>([\\s\\S]*?)</(?:\\w+:)?$name>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)?.trim()?.let(::unescape)

    fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    /** Samsung TVs know Matroska as video/x-mkv. */
    fun mime(contentType: String) = if (contentType == "video/x-matroska") "video/x-mkv" else contentType

    fun describe(location: String): DlnaDevice? {
        val xml = httpGet(location) ?: return null
        val services = Regex("<service>[\\s\\S]*?</service>", RegexOption.IGNORE_CASE).findAll(xml).map { it.value }
        val av = services.firstOrNull { (tag(it, "serviceType") ?: "").startsWith("urn:schemas-upnp-org:service:AVTransport:") } ?: return null
        val control = tag(av, "controlURL") ?: return null
        val base = tag(xml, "URLBase")?.takeIf { it.isNotBlank() } ?: location
        val host = URI(location).host
        return DlnaDevice(tag(xml, "UDN") ?: location, tag(xml, "friendlyName") ?: host, host, URL(URL(base), control).toString())
    }

    /** Finds DLNA renderers (Samsung and most other smart TVs) with an SSDP search. */
    fun discover(timeoutMs: Int = 3000): List<DlnaDevice> {
        val locations = linkedSetOf<String>()
        MulticastSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = 300
            val search = listOf(
                "M-SEARCH * HTTP/1.1", "HOST: 239.255.255.250:1900", "MAN: \"ssdp:discover\"", "MX: 2",
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1", "", ""
            ).joinToString("\r\n").toByteArray()
            val group = InetAddress.getByName("239.255.255.250")
            val end = System.currentTimeMillis() + timeoutMs
            var sent = 0
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                if (sent < 3) {
                    socket.send(DatagramPacket(search, search.size, group, 1900))
                    sent++
                }
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    Regex("^location:\\s*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
                        .find(String(packet.data, 0, packet.length))?.groupValues?.get(1)?.trim()?.let { locations += it }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return locations.mapNotNull { runCatching { describe(it) }.getOrNull() }.distinctBy { it.id }
    }

    fun parseDuration(value: String?): Double {
        if (value == null || !Regex("^\\d+:\\d{2}:\\d{2}").containsMatchIn(value)) return 0.0
        val (h, m, s) = value.split(":")
        return h.toDouble() * 3600 + m.toDouble() * 60 + s.toDouble()
    }

    fun clock(seconds: Double): String {
        val s = seconds.toLong().coerceAtLeast(0)
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    internal fun httpGet(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        return try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
        } finally {
            c.disconnect()
        }
    }
}

class DlnaPlayer(private val device: DlnaDevice) : CastPlayer {
    private fun soap(action: String, args: String): String {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"
        val c = URL(device.controlUrl).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) {
                val detail = Regex("<errorDescription>([^<]*)</errorDescription>").find(text)?.groupValues?.get(1) ?: "HTTP $code"
                error("The TV refused $action: $detail")
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    fun load(url: String, contentType: String, title: String, kind: String) {
        val upnpClass = when (kind) {
            "audio" -> "object.item.audioItem.musicTrack"
            "image" -> "object.item.imageItem.photo"
            else -> "object.item.videoItem"
        }
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${Dlna.escape(title)}</dc:title><upnp:class>$upnpClass</upnp:class>" +
            "<res protocolInfo=\"http-get:*:${Dlna.mime(contentType)}:${Dlna.FEATURES}\">${Dlna.escape(url)}</res></item></DIDL-Lite>"
        runCatching { soap("Stop", "") }
        soap("SetAVTransportURI", "<CurrentURI>${Dlna.escape(url)}</CurrentURI><CurrentURIMetaData>${Dlna.escape(didl)}</CurrentURIMetaData>")
        soap("Play", "<Speed>1</Speed>")
    }

    override fun play() { soap("Play", "<Speed>1</Speed>") }
    override fun pause() { soap("Pause", "") }
    override fun seek(seconds: Double) { soap("Seek", "<Unit>REL_TIME</Unit><Target>${Dlna.clock(seconds)}</Target>") }
    override fun stop() { runCatching { soap("Stop", "") } }

    override fun status(): CastPlayback? = runCatching {
        val transport = soap("GetTransportInfo", "")
        val position = soap("GetPositionInfo", "")
        fun tag(xml: String, name: String) = Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)
        CastPlayback(
            when (tag(transport, "CurrentTransportState")) {
                "PLAYING" -> "playing"; "PAUSED_PLAYBACK" -> "paused"; "TRANSITIONING" -> "buffering"; else -> "stopped"
            },
            Dlna.parseDuration(tag(position, "RelTime")),
            Dlna.parseDuration(tag(position, "TrackDuration"))
        )
    }.getOrNull()
}
