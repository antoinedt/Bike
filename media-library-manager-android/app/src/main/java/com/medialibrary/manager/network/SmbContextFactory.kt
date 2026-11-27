package com.medialibrary.manager.network

import com.medialibrary.manager.model.NetworkShare
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import java.util.Properties

/** Builds jcifs-ng SMB2/3 connection contexts and share URLs from our [NetworkShare] config. */
object SmbContextFactory {

    private val baseContext: CIFSContext by lazy {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
        }
        BaseContext(PropertyConfiguration(props))
    }

    fun contextFor(share: NetworkShare): CIFSContext {
        val auth = NtlmPasswordAuthenticator(share.domain.ifBlank { "WORKGROUP" }, share.username, share.password)
        return baseContext.withCredentials(auth)
    }

    /** The share root as an smb:// URL, trailing-slash terminated (required by jcifs for directories). */
    fun rootUrl(share: NetworkShare): String {
        val sub = share.subPath.trim('/', '\\').let { if (it.isEmpty()) "" else "$it/" }
        return "smb://${hostFor(share)}/${share.share}/$sub"
    }

    private data class Choice(val host: String, val at: Long)
    private val choices = java.util.concurrent.ConcurrentHashMap<String, Choice>()
    private const val CHOICE_TTL_MS = 30_000L
    private const val PROBE_TIMEOUT_MS = 1_500

    /**
     * The address to use for this share: its host, or — when that can't be reached and a backup
     * address is set (e.g. the computer's ZeroTier IP for when you're away from home) — the backup.
     * Checked with a quick connection to the SMB port, remembered for 30 s. Never probes on the
     * main thread (uses the last choice there).
     */
    fun hostFor(share: NetworkShare): String {
        val backup = share.backupHost.trim()
        if (backup.isEmpty() || backup == share.host) return share.host
        val key = "${share.id}|${share.host}|$backup"
        val cached = choices[key]
        val onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        if (cached != null && (onMain || System.currentTimeMillis() - cached.at < CHOICE_TTL_MS)) return cached.host
        if (onMain) return share.host
        val host = when {
            reachable(share.host) -> share.host
            reachable(backup) -> backup
            else -> share.host
        }
        if (cached?.host != host) {
            com.medialibrary.manager.log.AppLog.log("share", "${share.label}: using ${if (host == backup) "backup address" else "address"} $host")
        }
        choices[key] = Choice(host, System.currentTimeMillis())
        return host
    }

    /** Forget the remembered choice (e.g. after the phone switched networks). */
    fun forgetHostChoices() = choices.clear()

    private fun reachable(host: String): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, 445), PROBE_TIMEOUT_MS) }
        true
    }.getOrDefault(false)

    fun urlFor(share: NetworkShare, relPath: String): String {
        return rootUrl(share) + relPath.trimStart('/')
    }
}
