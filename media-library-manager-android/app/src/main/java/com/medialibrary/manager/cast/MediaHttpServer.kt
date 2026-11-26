package com.medialibrary.manager.cast

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Something a TV can stream: its size, and a stream starting at a byte offset. */
interface MediaSource {
    val size: Long
    fun open(offset: Long): InputStream
}

/**
 * Serves library items to a TV on the local network (the TV fetches the video itself), with Range
 * requests for seeking and the DLNA headers Samsung TVs look for. Each item gets an unguessable URL
 * that stops working when casting ends. Plain Kotlin (no Android), so it's tested on a desktop JVM.
 */
class MediaHttpServer {
    private class Entry(val source: () -> MediaSource, val contentType: String)

    private val entries = ConcurrentHashMap<String, Entry>()
    @Volatile private var server: ServerSocket? = null

    data class Published(val url: String, val token: String)

    @Synchronized
    private fun ensureStarted(): Int {
        server?.let { return it.localPort }
        val s = ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"))
        server = s
        thread(isDaemon = true, name = "cast-http") {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "cast-http-client") { runCatching { handle(client) }; runCatching { client.close() } }
            }
        }
        return s.localPort
    }

    fun publish(source: () -> MediaSource, name: String, contentType: String, deviceHost: String): Published {
        val port = ensureStarted()
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(source, contentType)
        val safeName = URLEncoder.encode(name.replace(Regex("[/\\\\?#]"), "_"), "UTF-8").replace("+", "%20")
        return Published("http://${lanAddressFor(deviceHost)}:$port/cast/$token/$safeName", token)
    }

    fun unpublish(token: String) {
        entries.remove(token)
    }

    @Synchronized
    fun stop() {
        entries.clear()
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0) ?: return
        val path = parts.getOrNull(1) ?: return
        val out = socket.getOutputStream()
        val token = Regex("^/cast/([0-9a-f-]{36})(/|$)").find(path)?.groupValues?.get(1)
        val entry = token?.let { entries[it] }
        if (entry == null) {
            respond(out, "404 Not Found", mapOf("Content-Length" to "0"))
            return
        }
        val source = runCatching { entry.source() }.getOrElse {
            respond(out, "500 Internal Server Error", mapOf("Content-Length" to "0"))
            return
        }
        val size = source.size
        var start = 0L
        var end = size - 1
        val range = Regex("^bytes=(\\d*)-(\\d*)$").find(headers["range"] ?: "")
        if (range != null) {
            val (a, b) = range.destructured
            if (a.isEmpty() && b.isNotEmpty()) {
                start = maxOf(0, size - b.toLong())
            } else {
                start = a.ifEmpty { "0" }.toLong()
                if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1)
            }
            if (start >= size || start > end) {
                respond(out, "416 Range Not Satisfiable", mapOf("Content-Range" to "bytes */$size", "Content-Length" to "0"))
                return
            }
        }
        val dlnaRequest = headers.containsKey("getcontentfeatures.dlna.org")
        val responseHeaders = linkedMapOf(
            "Content-Type" to if (dlnaRequest) Dlna.mime(entry.contentType) else entry.contentType,
            "Accept-Ranges" to "bytes",
            "Content-Length" to (end - start + 1).toString(),
            "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to Dlna.FEATURES,
            "Connection" to "close"
        )
        if (range != null) responseHeaders["Content-Range"] = "bytes $start-$end/$size"
        respond(out, if (range != null) "206 Partial Content" else "200 OK", responseHeaders)
        if (method == "HEAD") return
        source.open(start).use { stream ->
            val buffer = ByteArray(256 * 1024)
            var remaining = end - start + 1
            while (remaining > 0) {
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read == -1) break
                out.write(buffer, 0, read)
                remaining -= read
            }
            out.flush()
        }
    }

    private fun respond(out: OutputStream, status: String, headers: Map<String, String>) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }
        out.write(text.toByteArray())
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    companion object {
        /** This device's address that a TV at [deviceHost] can reach (same subnet if possible). */
        fun lanAddressFor(deviceHost: String): String {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress!! }
            val prefix = deviceHost.split(".").take(3).joinToString(".")
            return candidates.firstOrNull { it.startsWith("$prefix.") } ?: candidates.firstOrNull() ?: "127.0.0.1"
        }

        private val MIME = mapOf(
            "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo",
            "mov" to "video/quicktime", "wmv" to "video/x-ms-wmv", "mpg" to "video/mpeg", "mpeg" to "video/mpeg", "ts" to "video/mp2t",
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac", "wav" to "audio/wav",
            "ogg" to "audio/ogg", "opus" to "audio/ogg", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "gif" to "image/gif", "webp" to "image/webp"
        )

        fun contentTypeFor(extension: String) = MIME[extension.lowercase().removePrefix(".")] ?: "application/octet-stream"
    }
}
