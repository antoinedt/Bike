package com.medialibrary.manager.remote

import com.medialibrary.manager.model.NetworkShare
import com.medialibrary.manager.network.SmbContextFactory
import jcifs.smb.SmbFile

/** The mailbox folder on a network share (paths are relative to the share's configured folder). */
class SmbMailbox(share: NetworkShare) : Mailbox {
    private val context = SmbContextFactory.contextFor(share)
    private val base = SmbContextFactory.rootUrl(share)

    private fun file(path: String) = SmbFile(base + path.trimStart('/'), context)
    private fun dir(path: String) = SmbFile(base + path.trim('/') + "/", context)

    override fun list(dir: String): List<String> =
        runCatching { dir(dir).list().map { it.trimEnd('/') } }.getOrDefault(emptyList())

    override fun read(path: String): ByteArray = file(path).inputStream.use { it.readBytes() }

    override fun write(path: String, data: ByteArray) {
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) dir(parent).let { if (!it.exists()) it.mkdirs() }
        file(path).outputStream.use { it.write(data) }
    }

    override fun rename(from: String, to: String) = file(from).renameTo(file(to))

    override fun remove(path: String) = file(path).delete()
}
