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
        return "smb://${share.host}/${share.share}/$sub"
    }

    fun urlFor(share: NetworkShare, relPath: String): String {
        return rootUrl(share) + relPath.trimStart('/')
    }
}
