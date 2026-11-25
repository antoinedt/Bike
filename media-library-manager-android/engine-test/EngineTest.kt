import com.medialibrary.manager.torrent.*
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.util.Base64

lateinit var seed: Map<String, Any?>
var seederPort = 0
lateinit var httpBase: String
lateinit var seedDir: File
lateinit var root: File

class MemStore : TorrentStore {
    var saved: List<StoredTorrent> = emptyList()
    override fun load() = saved.map { it.copy() }
    override fun save(entries: List<StoredTorrent>) { saved = entries.map { it.copy() } }
}

fun localSettings() = SettingsPack().apply {
    setEnableDht(false); setEnableLsd(false)
    listenInterfaces("127.0.0.1:0")
    setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
    setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
    setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), false)
    setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), false)
}

fun t(name: String): Map<String, String> = @Suppress("UNCHECKED_CAST") ((seed["torrents"] as Map<String, Any?>)[name] as Map<String, String>)
fun check(cond: Boolean, msg: String) { if (!cond) throw AssertionError(msg) }
fun waitFor(timeoutMs: Long = 60_000, what: String = "condition", p: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!p()) { if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what"); Thread.sleep(50) }
}
fun same(a: File, b: File) = a.readBytes().contentEquals(b.readBytes())
fun connect(e: TorrentEngine, id: String) = e.connectPeer(id, "127.0.0.1", seederPort)

val tests = linkedMapOf<String, () -> Unit>(
    "magnet single file downloads, stops when done" to {
        val done = mutableListOf<TorrentSnapshot>()
        val e = TorrentEngine(MemStore(), localSettings()) { done += it }
        val dest = File(root, "dl1")
        val s = e.add(t("movie.mkv")["magnet"]!!, dest, "downloads")
        check(s.id == t("movie.mkv")["infoHash"], "id is info hash (${s.id})")
        connect(e, s.id)
        waitFor(what = "finish") { done.size == 1 }
        val l = e.list().single()
        check(l.state == TorrentState.DONE && l.progress == 1f, "done state $l")
        check(l.name == "movie.mkv", "name ${l.name}")
        check(l.destination == "downloads", "destination kept")
        check(same(File(seedDir, "movie.mkv"), File(dest, "movie.mkv")), "identical")
        check(!e.hasActive(), "nothing active")
        check(e.downloadedFile(s.id) == File(dest, "movie.mkv").canonicalFile, "downloadedFile")
        e.shutdown()
    },
    "multi-file from .torrent bytes" to {
        val done = mutableListOf<TorrentSnapshot>()
        val e = TorrentEngine(MemStore(), localSettings()) { done += it }
        val dest = File(root, "dl2")
        val s = e.add(Base64.getDecoder().decode(t("Show Season 1")["torrent"]), dest, "")
        check(s.name == "Show Season 1", "name known from .torrent (${s.name})")
        check(s.totalBytes == 2_700_000L, "size known from .torrent (${s.totalBytes})")
        connect(e, s.id)
        waitFor(what = "finish") { done.size == 1 }
        for (ep in listOf("ep1.mp4", "ep2.mp4")) check(same(File(seedDir, "Show Season 1/$ep"), File(dest, "Show Season 1/$ep")), "$ep identical")
        e.shutdown()
    },
    "http .torrent URL and redirect to magnet" to {
        val e = TorrentEngine(MemStore(), localSettings())
        val a = e.add("$httpBase/movie.torrent", File(root, "dl3"), "")
        check(a.id == t("movie.mkv")["infoHash"] && a.name == "movie.mkv", "via url $a")
        e.remove(a.id, false)
        val b = e.add("$httpBase/to-magnet", File(root, "dl3"), "")
        check(b.id == t("movie.mkv")["infoHash"], "via redirect")
        e.shutdown()
    },
    "duplicates keep one entry" to {
        val e = TorrentEngine(MemStore(), localSettings())
        e.add(t("movie.mkv")["magnet"]!!, File(root, "dl4"), "")
        e.add(Base64.getDecoder().decode(t("movie.mkv")["torrent"]), File(root, "dl4"), "")
        e.add(t("movie.mkv")["infoHash"]!!, File(root, "dl4"), "")
        check(e.list().size == 1, "one entry")
        e.shutdown()
    },
    "rejects non-torrents" to {
        val e = TorrentEngine(MemStore(), localSettings())
        fun rejects(src: Any, msg: String) {
            val err = runCatching { e.add(src, File(root, "x"), "") }.exceptionOrNull()
            check(err != null && err.message!!.contains(msg), "expected '$msg' for $src, got $err")
        }
        rejects("not a magnet", "magnet link")
        rejects("$httpBase/missing.torrent", "HTTP 404")
        rejects(byteArrayOf(1, 2, 3), "valid magnet link or .torrent")
        rejects("file:///etc/passwd", "magnet link")
        check(e.list().isEmpty(), "nothing added")
        e.shutdown()
    },
    "pause keeps data, resume completes" to {
        val done = mutableListOf<TorrentSnapshot>()
        val store = MemStore()
        val e = TorrentEngine(store, localSettings()) { done += it }
        val dest = File(root, "dl5")
        val id = e.add(t("big.mkv")["magnet"]!!, dest, "").id
        connect(e, id)
        waitFor(what = "10%") { e.list()[0].progress > 0.1f }
        e.pause(id)
        val p = e.list()[0]
        check(p.state == TorrentState.PAUSED, "paused state ${p.state}")
        check(p.progress > 0.1f && p.progress < 1f, "progress kept ${p.progress}")
        check(p.downloadRate == 0L, "no speed while paused")
        check(store.saved[0].paused && store.saved[0].resumeData != null, "resume data saved on pause")
        val before = p.doneBytes
        Thread.sleep(1500)
        check(e.list()[0].doneBytes == before, "nothing downloads while paused")
        e.resume(id)
        connect(e, id)
        waitFor(what = "finish after resume") { done.size == 1 }
        check(same(File(seedDir, "big.mkv"), File(dest, "big.mkv")), "identical after pause/resume")
        e.shutdown()
    },
    "restart resumes unfinished, finished stay finished" to {
        val store = MemStore()
        val first = TorrentEngine(store, localSettings())
        val dest = File(root, "dl6")
        val id = first.add(t("big.mkv")["magnet"]!!, dest, "share:x").id
        connect(first, id)
        waitFor(what = "5%") { first.list()[0].progress > 0.05f }
        first.shutdown()
        val savedProgress = store.saved[0].progress
        check(store.saved[0].resumeData != null && savedProgress > 0.05f, "resume data + progress saved at shutdown ($savedProgress)")

        val done = mutableListOf<TorrentSnapshot>()
        val second = TorrentEngine(store, localSettings()) { done += it }
        second.start()
        val s = second.list().single()
        check(s.state != TorrentState.PAUSED && s.destination == "share:x", "restarted by itself: $s")
        check(s.name == "big.mkv", "name restored")
        connect(second, id)
        waitFor(what = "finish after restart") { done.size == 1 }
        check(same(File(seedDir, "big.mkv"), File(dest, "big.mkv")), "identical after restart")
        second.setDelivery(id, "")
        second.shutdown()

        val third = TorrentEngine(store, localSettings())
        third.start()
        check(third.list()[0].state == TorrentState.DONE && third.list()[0].delivery == "", "still done + delivered")
        check(!third.hasActive(), "not restarted")
        third.shutdown()
    },
    "remove keeps or deletes only its own files" to {
        val done = mutableListOf<TorrentSnapshot>()
        val e = TorrentEngine(MemStore(), localSettings()) { done += it }
        val dest = File(root, "dl7").apply { mkdirs() }
        File(dest, "unrelated.txt").writeText("keep me")
        val a = e.add(t("movie.mkv")["magnet"]!!, dest, "")
        val b = e.add(t("Show Season 1")["magnet"]!!, dest, "")
        connect(e, a.id); connect(e, b.id)
        waitFor(what = "both finish") { done.size == 2 }
        e.remove(a.id, false)
        check(File(dest, "movie.mkv").exists(), "kept")
        e.remove(b.id, true)
        check(!File(dest, "Show Season 1").exists(), "folder deleted")
        check(File(dest, "unrelated.txt").exists(), "other files untouched")
        check(e.list().isEmpty(), "list empty")
        e.shutdown()
    }
)

fun main(args: Array<String>) {
    seedDir = File(args[0]); root = File(args[1])
    val json = File(args[2]).readText()
    // Minimal JSON reading via the JDK's JavaScript-free path: hand-parse with regex for the few fields.
    seederPort = Regex("\"port\":(\\d+)").find(json)!!.groupValues[1].toInt()
    httpBase = Regex("\"http\":\"([^\"]+)\"").find(json)!!.groupValues[1]
    val torrents = mutableMapOf<String, Any?>()
    for (name in listOf("movie.mkv", "Show Season 1", "big.mkv")) {
        val block = json.substringAfter("\"$name\":{").substringBefore("}")
        torrents[name] = mapOf(
            "magnet" to Regex("\"magnet\":\"([^\"]+)\"").find(block)!!.groupValues[1],
            "infoHash" to Regex("\"infoHash\":\"([^\"]+)\"").find(block)!!.groupValues[1],
            "torrent" to Regex("\"torrent\":\"([^\"]+)\"").find(block)!!.groupValues[1]
        )
    }
    seed = mapOf("torrents" to torrents)
    var failed = 0
    for ((name, body) in tests) {
        val started = System.currentTimeMillis()
        try { body(); println("ok   $name (${System.currentTimeMillis() - started} ms)") }
        catch (e: Throwable) { failed++; println("FAIL $name: $e"); e.printStackTrace() }
    }
    println(if (failed == 0) "ALL ${tests.size} PASSED" else "$failed FAILED")
    System.exit(if (failed == 0) 0 else 1)
}
