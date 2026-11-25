package com.medialibrary.manager.network

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import jcifs.CIFSContext
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A Media3 [DataSource] that streams an SMB file straight into ExoPlayer — real seeking, nothing
 * downloaded up front, no local server (the desktop app needs a loopback HTTP relay for this; here
 * ExoPlayer reads in-process).
 *
 * Reads go through [ParallelSmbReader] rather than one SmbRandomAccessFile: jcifs-ng 2.1.x caps
 * each SMB2 read at ~64 KB and waits for each reply before sending the next request, so a single
 * handle's throughput is ~64 KB per network round trip — a few MB/s over Wi-Fi, below what a
 * high-bitrate movie needs, however fast the network is. (Raising the read size isn't an option:
 * this jcifs-ng version doesn't send the SMB2 credit charge larger reads require.)
 */
@UnstableApi
class SmbDataSource(private val cifsContext: CIFSContext) : BaseDataSource(/* isNetwork= */ true) {

    private var uri: Uri? = null
    private var reader: ParallelSmbReader? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val file = SmbFile(dataSpec.uri.toString(), cifsContext)
        val fileLength = file.length()
        if (dataSpec.position > fileLength) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        val available = fileLength - dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, available) else available
        reader = ParallelSmbReader(dataSpec.uri.toString(), cifsContext, dataSpec.position, dataSpec.position + bytesRemaining)
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val active = reader ?: throw IOException("Stream is not open")
        val read = active.read(buffer, offset, minOf(length.toLong(), bytesRemaining).toInt())
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            reader?.close()
        } finally {
            reader = null
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /** [cifsContext] is pre-resolved (credentials looked up) by the caller before playback starts. */
    class Factory(private val cifsContext: CIFSContext) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(cifsContext)
    }
}

/**
 * Reads [start, end) of an SMB file as fixed-size chunks fetched by several threads at once, each
 * with its own file handle, keeping up to [AHEAD_CHUNKS] chunks in flight ahead of the read
 * position. Throughput becomes [PARALLEL] round trips' worth of data per round trip instead of one.
 */
internal class ParallelSmbReader(
    private val url: String,
    private val cifsContext: CIFSContext,
    private val start: Long,
    private val end: Long
) : Closeable {

    private class Handle {
        var raf: SmbRandomAccessFile? = null
    }

    private val executor = Executors.newFixedThreadPool(PARALLEL) { runnable ->
        Thread(runnable, "smb-read").apply { isDaemon = true }
    }
    private val handles = ArrayBlockingQueue<Handle>(PARALLEL).apply { repeat(PARALLEL) { add(Handle()) } }
    private val allHandles = mutableListOf<SmbRandomAccessFile>()
    private val chunks = HashMap<Long, Future<ByteArray>>()
    private var position = start
    private var nextToSchedule = start

    private fun chunkStartFor(offset: Long): Long = start + ((offset - start) / CHUNK_SIZE) * CHUNK_SIZE

    private fun fetch(chunkStart: Long): ByteArray {
        val length = minOf(CHUNK_SIZE.toLong(), end - chunkStart).toInt()
        val handle = handles.take()
        try {
            // Each handle gets its own SmbFile: they're used from different threads at once.
            val raf = handle.raf ?: SmbRandomAccessFile(SmbFile(url, cifsContext), "r").also {
                handle.raf = it
                synchronized(allHandles) { allHandles += it }
            }
            val startedAt = SystemClock.elapsedRealtime()
            raf.seek(chunkStart)
            val data = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val read = raf.read(data, filled, length - filled)
                if (read <= 0) break
                filled += read
            }
            SmbStreamStats.record(filled, SystemClock.elapsedRealtime() - startedAt)
            return if (filled == length) data else data.copyOf(filled)
        } finally {
            handles.put(handle)
        }
    }

    private fun scheduleAhead() {
        val limit = minOf(end, chunkStartFor(position) + AHEAD_CHUNKS.toLong() * CHUNK_SIZE)
        while (nextToSchedule < limit) {
            val chunkStart = nextToSchedule
            chunks[chunkStart] = executor.submit<ByteArray> { fetch(chunkStart) }
            nextToSchedule += CHUNK_SIZE
        }
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= end) return -1
        scheduleAhead()
        val chunkStart = chunkStartFor(position)
        val pending = chunks[chunkStart] ?: throw IOException("Chunk at $chunkStart was never requested")
        val data = try {
            pending.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while waiting for SMB data")
        } catch (e: ExecutionException) {
            val cause = e.cause
            throw cause as? IOException ?: IOException(cause)
        }
        val within = (position - chunkStart).toInt()
        if (within >= data.size) return -1 // server returned less than asked: end of file
        val count = minOf(length, data.size - within)
        System.arraycopy(data, within, buffer, offset, count)
        position += count
        if (position - chunkStart >= data.size) chunks.remove(chunkStart)
        return count
    }

    /**
     * Doesn't interrupt reads already on the wire (interrupting a jcifs-ng request can break the
     * shared connection); lets them finish in the background, then closes the file handles.
     */
    override fun close() {
        chunks.values.forEach { it.cancel(false) }
        chunks.clear()
        executor.shutdown()
        Thread {
            runCatching { executor.awaitTermination(30, TimeUnit.SECONDS) }
            synchronized(allHandles) {
                allHandles.forEach { runCatching { it.close() } }
                allHandles.clear()
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        const val CHUNK_SIZE = 256 * 1024
        const val PARALLEL = 8
        const val AHEAD_CHUNKS = 32 // ~8 MB read ahead of the player
    }
}

/** Running totals for the player's diagnostics panel: how fast SMB data is actually arriving. */
object SmbStreamStats {
    private val bytes = AtomicLong()
    private val chunks = AtomicLong()
    private val fetchMillis = AtomicLong()

    data class Snapshot(val bytes: Long, val chunks: Long, val fetchMillis: Long, val atMillis: Long)

    fun record(byteCount: Int, millis: Long) {
        bytes.addAndGet(byteCount.toLong())
        chunks.incrementAndGet()
        fetchMillis.addAndGet(millis)
    }

    fun snapshot() = Snapshot(bytes.get(), chunks.get(), fetchMillis.get(), SystemClock.elapsedRealtime())
}
