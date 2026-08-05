package com.medialibrary.manager.network

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.CIFSContext
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException

/**
 * A Media3 [DataSource] backed by jcifs-ng random-access reads, so ExoPlayer can play an SMB
 * file directly — real seeking, real buffering, nothing downloaded up front. This is the
 * Android equivalent of the desktop app's loopback HTTP relay (smbRawClient.ts +
 * streamServer.ts), but simpler: jcifs-ng's SmbRandomAccessFile already does offset/length
 * reads, so there's no need to reach into protocol internals the way the Node smb2 package
 * required, and no local HTTP server is needed either — ExoPlayer talks to this DataSource
 * in-process.
 */
@UnstableApi
class SmbDataSource(private val cifsContext: CIFSContext) : BaseDataSource(/* isNetwork= */ true) {

    private var raf: SmbRandomAccessFile? = null
    private var dataSpecUri: Uri? = null
    private var bytesRemaining: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        dataSpecUri = dataSpec.uri
        transferInitializing(dataSpec)

        val smbFile = SmbFile(dataSpec.uri.toString(), cifsContext)
        val fileLength = smbFile.length()
        val randomAccessFile = SmbRandomAccessFile(smbFile, "r")
        randomAccessFile.seek(dataSpec.position)
        raf = randomAccessFile

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            fileLength - dataSpec.position
        }
        if (bytesRemaining < 0) {
            throw IOException("Requested position ${dataSpec.position} is past end of file ($fileLength bytes)")
        }

        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining < length) bytesRemaining.toInt() else length
        val bytesRead = raf?.read(buffer, offset, bytesToRead) ?: throw IOException("Stream is not open")
        if (bytesRead == -1) return C.RESULT_END_OF_INPUT

        bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = dataSpecUri

    override fun close() {
        try {
            raf?.close()
        } finally {
            raf = null
            if (dataSpecUri != null) {
                dataSpecUri = null
                transferEnded()
            }
        }
    }

    /** [cifsContext] is pre-resolved (credentials looked up) by the caller before playback starts. */
    class Factory(private val cifsContext: CIFSContext) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(cifsContext)
    }
}
