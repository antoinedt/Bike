import com.medialibrary.manager.remote.JobOutcome
import com.medialibrary.manager.remote.Mailbox
import com.medialibrary.manager.remote.Me
import com.medialibrary.manager.remote.RemoteJob
import com.medialibrary.manager.remote.RemoteQueue
import com.medialibrary.manager.remote.RemoteResult
import java.io.File
import java.util.Base64

// The Android side of the "Send to another device" interop check (remote-interop.mjs is the
// desktop side): both write and read the same mailbox folder.

class FolderMailbox(private val root: File) : Mailbox {
    private fun f(p: String) = File(root, p)
    override fun list(dir: String) = f(dir).list()?.toList() ?: emptyList()
    override fun read(path: String) = f(path).readBytes()
    override fun write(path: String, data: ByteArray) { f(path).parentFile.mkdirs(); f(path).writeBytes(data) }
    override fun rename(from: String, to: String) { check(f(from).renameTo(f(to))) { "rename $from" } }
    override fun remove(path: String) { check(f(path).delete()) { "remove $path" } }
}

fun main(args: Array<String>) {
    val root = File(args[0])
    val step = args[1]
    val jobs = mutableListOf<RemoteJob>()
    val results = mutableListOf<RemoteResult>()
    val tv = RemoteQueue(FolderMailbox(root), Me("tv-1", "Living room TV", "android-tv"), onJob = { job ->
        jobs += job
        JobOutcome(true, "Downloading", if (job.torrent != null) "From torrent file" else null)
    }, onResult = { results += it })
    fun check(cond: Boolean, msg: String) { if (!cond) { println("FAIL $msg"); kotlin.system.exitProcess(1) } }
    when (step) {
        "receive" -> {
            tv.heartbeat()
            val devices = tv.devices()
            check(devices.map { it.id to it.name } == listOf("pc-1" to "Office PC"), "Android sees the PC: $devices")
            check(devices[0].isOnline(), "PC online")
            tv.poll()
            check(jobs.size == 2, "Android got both jobs: $jobs")
            val magnet = jobs.first { it.magnet != null }
            check(magnet.magnet!!.endsWith("dn=Some+Movie") && magnet.fromName == "Office PC" && magnet.name == "Some Movie", "magnet job $magnet")
            val torrent = Base64.getDecoder().decode(jobs.first { it.torrent != null }.torrent)
            check(torrent.size == 3000 && torrent.withIndex().all { (i, b) -> b == (i % 251).toByte() }, ".torrent bytes intact")
            tv.send("pc-1", "magnet:?xt=urn:btih:bbbb&dn=From+TV", null, RemoteQueue.magnetName("magnet:?xt=urn:btih:bbbb&dn=From+TV")!!)
            println("ok   Android received the PC's magnet + .torrent, and sent one back")
        }
        "replies" -> {
            tv.poll()
            check(results.size == 1 && results[0].ok && results[0].toName == "Office PC" && results[0].name == "From TV", "Android heard back: $results")
            println("ok   Android got the PC's reply")
        }
    }
}
