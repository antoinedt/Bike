package com.medialibrary.manager.ui.components

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.medialibrary.manager.network.ParallelSmbReader
import com.medialibrary.manager.network.SmbStreamStats

/** What the diagnostics panel shows: one line per fact, plus the full error if playback failed. */
data class DiagnosticsSnapshot(
    val lines: List<String>,
    val buffering: Boolean,
    val error: String?
)

/**
 * Collects what's needed to explain a playback problem without a debugger: which decoders the
 * device picked (or that none could handle a track), dropped frames, audio output trouble, the
 * exact error, and — for network items — how fast data is really arriving from the share.
 */
@UnstableApi
class PlaybackDiagnostics : AnalyticsListener {
    @Volatile private var videoDecoder: String? = null
    @Volatile private var audioDecoder: String? = null
    @Volatile private var droppedFrames = 0
    @Volatile private var audioUnderruns = 0
    @Volatile private var audioOutputError: String? = null
    @Volatile private var lastError: PlaybackException? = null

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        videoDecoder = decoderName
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        audioDecoder = decoderName
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        this.droppedFrames += droppedFrames
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long
    ) {
        audioUnderruns++
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        audioOutputError = audioSinkError.message ?: audioSinkError.javaClass.simpleName
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        lastError = error
    }

    /**
     * Builds the panel's contents. Call on the main thread. [network] is the SMB byte counters at
     * the start and end of the last interval (null for a local file).
     */
    fun snapshot(
        player: ExoPlayer,
        container: String,
        network: Pair<SmbStreamStats.Snapshot, SmbStreamStats.Snapshot>?
    ): DiagnosticsSnapshot {
        val lines = mutableListOf<String>()
        val state = when (player.playbackState) {
            Player.STATE_BUFFERING -> "Buffering…"
            Player.STATE_READY -> if (player.isPlaying) "Playing" else "Paused"
            Player.STATE_ENDED -> "Ended"
            else -> "Stopped"
        }
        lines += "$state · ${formatTime(player.currentPosition)} / ${formatTime(player.duration)} · " +
            "buffered ahead %.1f s · %s file".format(player.totalBufferedDuration / 1000.0, container.uppercase())

        if (network != null) {
            val (from, to) = network
            val millis = (to.atMillis - from.atMillis).coerceAtLeast(1)
            val mbps = (to.bytes - from.bytes) * 8.0 / 1000.0 / millis
            val chunks = to.chunks - from.chunks
            lines += if (chunks > 0) {
                "Network: %.1f Mbit/s · each 256 KB read takes %d ms (%d in parallel)"
                    .format(mbps, (to.fetchMillis - from.fetchMillis) / chunks, ParallelSmbReader.PARALLEL)
            } else {
                "Network: no data in the last second (buffer full, paused, or the share stopped answering)"
            }
        }

        player.videoFormat?.let { f ->
            lines += "Video: ${codecName(f)} · ${f.width}×${f.height}${bitrate(f)} · decoder ${videoDecoder ?: "not started"}"
        }
        player.audioFormat?.let { f ->
            lines += "Audio: ${codecName(f)} · ${f.channelCount} ch · ${f.sampleRate} Hz${bitrate(f)} · " +
                "decoder ${audioDecoder ?: "none (sent as-is to the TV/receiver, or not started)"}"
        }
        for (group in player.currentTracks.groups) {
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) {
                    lines += "Not playable on this device: ${trackType(group.type)} ${codecName(group.getTrackFormat(i))}"
                }
            }
        }
        if (droppedFrames > 0) lines += "Dropped video frames: $droppedFrames (the decoder can't keep up with this file)"
        if (audioUnderruns > 0) lines += "Audio dropouts: $audioUnderruns"
        audioOutputError?.let { lines += "Audio output error: $it" }

        val error = (player.playerError ?: lastError)?.let { e ->
            buildString {
                append(e.errorCodeName)
                e.message?.let { append(": ").append(it) }
                var cause = e.cause
                var depth = 0
                while (cause != null && depth < 4) {
                    append("\n  caused by ").append(cause.javaClass.simpleName)
                    cause.message?.let { append(": ").append(it) }
                    cause = cause.cause
                    depth++
                }
            }
        }
        return DiagnosticsSnapshot(lines, player.playbackState == Player.STATE_BUFFERING, error)
    }

    private fun formatTime(ms: Long): String {
        if (ms == C.TIME_UNSET || ms < 0) return "--:--"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun bitrate(f: Format): String =
        if (f.bitrate != Format.NO_VALUE) " · %.1f Mbit/s".format(f.bitrate / 1_000_000.0) else ""

    private fun trackType(type: Int) = when (type) {
        C.TRACK_TYPE_VIDEO -> "video"
        C.TRACK_TYPE_AUDIO -> "audio"
        C.TRACK_TYPE_TEXT -> "subtitles"
        else -> "track"
    }

    private fun codecName(f: Format): String {
        val mime = f.sampleMimeType ?: f.containerMimeType ?: "unknown"
        val name = when (mime) {
            "video/avc" -> "H.264"
            "video/hevc" -> "H.265/HEVC"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/x-vnd.on2.vp8" -> "VP8"
            "video/av01" -> "AV1"
            "video/mp4v-es" -> "MPEG-4 Part 2 (DivX/Xvid)"
            "video/mpeg2" -> "MPEG-2"
            "video/wvc1", "video/vc1" -> "VC-1"
            "audio/mp4a-latm" -> "AAC"
            "audio/ac3" -> "Dolby Digital (AC3)"
            "audio/eac3" -> "Dolby Digital Plus (E-AC3)"
            "audio/eac3-joc" -> "Dolby Atmos (E-AC3)"
            "audio/true-hd" -> "Dolby TrueHD"
            "audio/vnd.dts" -> "DTS"
            "audio/vnd.dts.hd" -> "DTS-HD"
            "audio/mpeg" -> "MP3"
            "audio/opus" -> "Opus"
            "audio/vorbis" -> "Vorbis"
            "audio/flac" -> "FLAC"
            else -> mime
        }
        return if (f.codecs != null) "$name [${f.codecs}]" else name
    }
}
