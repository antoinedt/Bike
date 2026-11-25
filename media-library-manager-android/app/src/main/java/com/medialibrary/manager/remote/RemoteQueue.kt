package com.medialibrary.manager.remote

import org.json.JSONObject
import java.util.Base64
import java.util.UUID

// "Send to another device": a mailbox in a hidden folder on a network share both devices use.
//
//   .media-library-manager/devices/<deviceId>.json        who's out there (refreshed while the app is open)
//   .media-library-manager/inbox/<deviceId>/<job>.json    downloads waiting for that device
//   .media-library-manager/results/<deviceId>/<job>.json  "started it" / "couldn't" replies to the sender
//
// Same format as the desktop app's remoteQueue.ts — keep them in step. Files are written under a
// ".tmp" name and renamed when complete, so a reader never sees half a file. No Android imports
// (org.json ships with Android; the JVM tests add it), so this is tested on a desktop JVM.

const val MAILBOX_ROOT = ".media-library-manager"
/** A device counts as online if it checked in this recently. */
const val ONLINE_MS = 3 * 60 * 1000L

interface Mailbox {
    /** File names in a folder (empty if it doesn't exist). */
    fun list(dir: String): List<String>
    fun read(path: String): ByteArray
    /** Creates parent folders as needed; overwrites. */
    fun write(path: String, data: ByteArray)
    fun rename(from: String, to: String)
    fun remove(path: String)
}

data class RemoteDevice(val id: String, val name: String, val platform: String, val lastSeen: Long) {
    fun isOnline(now: Long = System.currentTimeMillis()) = now - lastSeen < ONLINE_MS
}

data class RemoteJob(
    val id: String,
    val from: String,
    val fromName: String,
    val magnet: String?,
    /** Base64 .torrent file. */
    val torrent: String?,
    val name: String,
    val createdAt: Long
)

data class RemoteResult(
    val jobId: String,
    val to: String,
    val toName: String,
    val ok: Boolean,
    val message: String,
    val name: String,
    val at: Long
)

data class JobOutcome(val ok: Boolean, val message: String, val name: String? = null)

data class Me(val id: String, val name: String, val platform: String)

class RemoteQueue(
    private val mailbox: Mailbox,
    private val me: Me,
    /** Start the download; return what to tell the sender. */
    private val onJob: (RemoteJob) -> JobOutcome,
    private val onResult: (RemoteResult) -> Unit = {}
) {
    private fun writeJson(path: String, json: JSONObject) {
        val tmp = "$path.tmp"
        mailbox.write(tmp, json.toString(2).toByteArray())
        runCatching { mailbox.remove(path) }
        mailbox.rename(tmp, path)
    }

    private fun readJson(path: String): JSONObject? = runCatching { JSONObject(String(mailbox.read(path))) }.getOrNull()

    /** Says "I'm here" so other devices list this one. */
    fun heartbeat(now: Long = System.currentTimeMillis()) {
        writeJson(
            "$MAILBOX_ROOT/devices/${me.id}.json",
            JSONObject().put("id", me.id).put("name", me.name).put("platform", me.platform).put("lastSeen", now)
        )
    }

    /** Other devices that have checked in, most recently seen first. */
    fun devices(): List<RemoteDevice> {
        val dir = "$MAILBOX_ROOT/devices"
        return mailbox.list(dir).filter { it.endsWith(".json") }.mapNotNull { name ->
            val o = readJson("$dir/$name") ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isEmpty() || id == me.id) null
            else RemoteDevice(id, o.optString("name", id), o.optString("platform"), o.optLong("lastSeen"))
        }.sortedByDescending { it.lastSeen }
    }

    /** Puts a download (a magnet link, or .torrent bytes) in another device's inbox. */
    fun send(toId: String, magnet: String?, torrent: ByteArray?, name: String, now: Long = System.currentTimeMillis()): RemoteJob {
        require(magnet != null || torrent != null) { "Nothing to send." }
        val job = RemoteJob(
            id = UUID.randomUUID().toString(),
            from = me.id,
            fromName = me.name,
            magnet = magnet,
            torrent = if (magnet == null) Base64.getEncoder().encodeToString(torrent) else null,
            name = name,
            createdAt = now
        )
        val json = JSONObject().put("id", job.id).put("from", job.from).put("fromName", job.fromName)
            .put("name", job.name).put("createdAt", job.createdAt)
        if (job.magnet != null) json.put("magnet", job.magnet) else json.put("torrent", job.torrent)
        writeJson("$MAILBOX_ROOT/inbox/$toId/$now-${job.id}.json", json)
        return job
    }

    /** Starts anything waiting in this device's inbox, and picks up replies to what it sent. */
    fun poll() {
        val inbox = "$MAILBOX_ROOT/inbox/${me.id}"
        for (name in mailbox.list(inbox).filter { it.endsWith(".json") }.sorted()) {
            val path = "$inbox/$name"
            val o = readJson(path)
            // Take it out of the inbox first, so a crash can't start it twice.
            runCatching { mailbox.remove(path) }
            if (o == null || o.optString("id").isEmpty() || o.optString("from").isEmpty()) continue
            val job = RemoteJob(
                id = o.getString("id"),
                from = o.getString("from"),
                fromName = o.optString("fromName", "another device"),
                magnet = o.optString("magnet").ifEmpty { null },
                torrent = o.optString("torrent").ifEmpty { null },
                name = o.optString("name", "Download"),
                createdAt = o.optLong("createdAt")
            )
            val outcome = runCatching { onJob(job) }.getOrElse { JobOutcome(false, it.message ?: "Couldn't start it.") }
            runCatching {
                writeJson(
                    "$MAILBOX_ROOT/results/${job.from}/${job.id}.json",
                    JSONObject().put("jobId", job.id).put("to", me.id).put("toName", me.name).put("ok", outcome.ok)
                        .put("message", outcome.message).put("name", outcome.name ?: job.name).put("at", System.currentTimeMillis())
                )
            }
        }

        val results = "$MAILBOX_ROOT/results/${me.id}"
        for (name in mailbox.list(results).filter { it.endsWith(".json") }) {
            val path = "$results/$name"
            val o = readJson(path)
            runCatching { mailbox.remove(path) }
            if (o != null) {
                onResult(
                    RemoteResult(
                        o.optString("jobId"), o.optString("to"), o.optString("toName", "the other device"), o.optBoolean("ok"),
                        o.optString("message"), o.optString("name"), o.optLong("at")
                    )
                )
            }
        }
    }

    companion object {
        /** "magnet:?xt=…&dn=Name" → "Name". */
        fun magnetName(magnet: String): String? {
            val raw = Regex("[?&]dn=([^&]+)").find(magnet)?.groupValues?.get(1) ?: return null
            return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }
    }
}
