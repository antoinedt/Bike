import com.medialibrary.manager.cast.CastV2Session
import com.medialibrary.manager.cast.Dlna
import com.medialibrary.manager.cast.DlnaPlayer
import com.medialibrary.manager.cast.MediaHttpServer
import com.medialibrary.manager.cast.MediaSource
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

// Android's casting code on a desktop JVM: Google Cast against fake-chromecast.mjs (castv2's
// receiver), and DLNA against a real renderer (gmediarender), both streaming from MediaHttpServer.

class FileSource(private val file: File) : MediaSource {
    override val size get() = file.length()
    override fun open(offset: Long): InputStream = FileInputStream(file).also { it.channel.position(offset) }
}

fun die(msg: String): Nothing { println("FAIL $msg"); kotlin.system.exitProcess(1) }
fun expect(cond: Boolean, msg: String) { if (!cond) die(msg) }
fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

fun main(args: Array<String>) {
    val mode = args[0]
    val server = MediaHttpServer()
    if (mode == "cast") {
        val (port, movieFile, reportFile) = Triple(args[1].toInt(), File(args[2]), File(args[3]))
        val movie = movieFile.readBytes()
        val published = server.publish({ FileSource(movieFile) }, "Movie (2021).mkv", MediaHttpServer.contentTypeFor("mkv"), "127.0.0.1")
        val session = CastV2Session.connect("127.0.0.1", port)
        session.load(published.url, MediaHttpServer.contentTypeFor("mkv"), "Movie")
        Thread.sleep(500)
        expect(session.status()?.state == "playing", "playing after load: ${session.status()}")
        session.pause(); Thread.sleep(400)
        expect(session.status()?.state == "paused", "paused")
        session.seek(1200.0); Thread.sleep(400)
        session.play(); Thread.sleep(400)
        val s = session.status()
        expect(s?.state == "playing" && s.position == 1200.0 && s.duration == 5400.0, "playing at 1200/5400: $s")
        session.stop()
        Thread.sleep(500)
        val report = reportFile.readText()
        expect(report.contains("\"sha\":\"${sha256Hex(movie)}\""), "TV downloaded the whole file intact: $report")
        expect(report.contains("\"rangeStatus\":206") && report.contains("\"range\":\"${movie.copyOfRange(1000000, 1000100).joinToString("") { "%02x".format(it) }}\""), "Range request served")
        expect(report.contains("\"contentType\":\"video/x-matroska\"") && report.contains("heartbeat:PONG") && report.contains("receiver:STOP"), "protocol: $report")
        println("ok   Android Cast client: launch, load, TV streamed the file (+ range), pause, seek, play, stop")
    } else if (mode == "castlive") {
        val (port, reportFile) = Pair(args[1].toInt(), File(args[2]))
        val session = CastV2Session.connect("127.0.0.1", port)
        // A protected live channel: the Fizz receiver plays the remote stream (nothing is served here).
        val stream = com.medialibrary.manager.cast.CastLiveStream(
            url = "https://cdn.example/live/ch.mpd",
            contentType = "video/mp4",
            mediaFormatId = 2,
            drmToken = "tok-abc",
            drmLicenseUrl = "https://widevine-proxy.drm.technology/proxy",
            drmType = 1,
            drmContentId = "cid-xyz"
        )
        session.loadLive(stream, "CTV")
        Thread.sleep(400)
        expect(session.status()?.state == "playing", "playing after loadLive: ${session.status()}")
        session.stop()
        Thread.sleep(500)
        val report = reportFile.readText()
        expect(report.contains("\"appId\":\"559B0340\""), "launched Fizz receiver 559B0340: $report")
        expect(report.contains("\"streamType\":\"LIVE\""), "live stream type: $report")
        expect(report.contains("\"drm_token\":\"tok-abc\"") && report.contains("\"drm_license_url\":\"https://widevine-proxy.drm.technology/proxy\""), "drm token + licence url: $report")
        expect(report.contains("\"drm_type\":1") && report.contains("\"contentId\":\"cid-xyz\"") && report.contains("\"channel_name\":\"CTV\""), "drm type/content-id/channel: $report")
        println("ok   Android Cast live TV: launched Fizz receiver 559B0340 with the DRM token/type/content-id metadata")
    } else {
        val wav = File(args[1])
        val tv = Dlna.discover(3000).firstOrNull { it.name == "Living Room Samsung" } ?: die("DLNA renderer not discovered")
        val published = server.publish({ FileSource(wav) }, "Tone (test).wav", MediaHttpServer.contentTypeFor("wav"), tv.host)
        val player = DlnaPlayer(tv)
        player.load(published.url, MediaHttpServer.contentTypeFor("wav"), "Tone (test)", "audio")
        Thread.sleep(3000)
        val playing = player.status()
        expect(playing?.state == "playing" && playing.position >= 1 && playing.duration == 30.0, "playing: $playing")
        player.pause(); Thread.sleep(1200)
        val paused = player.status()
        Thread.sleep(1500)
        expect(paused?.state == "paused" && player.status()?.position == paused.position, "paused and holding: $paused")
        player.seek(20.0); player.play(); Thread.sleep(1500)
        val after = player.status()
        expect(after?.state == "playing" && after.position >= 20, "after seek to 20 s: $after")
        player.stop(); Thread.sleep(800)
        expect(player.status()?.state == "stopped", "stopped")
        println("ok   Android DLNA client: found ${tv.name} via SSDP, load, play, pause, seek, stop")
    }
    server.stop()
    kotlin.system.exitProcess(0)
}
