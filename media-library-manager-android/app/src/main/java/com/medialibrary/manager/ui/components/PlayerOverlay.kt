package com.medialibrary.manager.ui.components

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.network.SmbStreamStats
import kotlinx.coroutines.delay
import androidx.media3.common.MediaItem as ExoMediaItem

/**
 * Full-screen in-app playback, for network items (streamed live from the share via
 * SmbDataSource) and local video/audio alike: black background, the video letterboxed to fit,
 * system bars hidden, screen kept on, no title. Back closes it.
 *
 * Playback info (top left: buffer level, real network speed, codecs and the decoders the device
 * picked, dropped frames) is off by default; the ⚙ button shown with the controls turns it on, and
 * the choice is remembered. A playback error is always shown, so a file that won't play says why.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerOverlay(
    item: MediaItem,
    streamUrl: String,
    dataSourceFactory: DataSource.Factory,
    isNetwork: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val diagnostics = remember { PlaybackDiagnostics() }
    val player = remember {
        // Decoder fallback: if the preferred (usually hardware) decoder fails to start for a
        // file, try the device's other decoders before giving up.
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus= */ true
            )
            .build()
            .apply {
                addAnalyticsListener(diagnostics)
                setMediaItem(ExoMediaItem.fromUri(streamUrl))
                prepare()
                playWhenReady = true
            }
    }
    // Resume where you left off: the position is saved every few seconds and when closing.
    val resume = remember { ResumePositions(context) }
    var resumedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(player) {
        resume.get(item.id)?.let { at ->
            player.seekTo(at)
            resumedAt = at
        }
        while (true) {
            delay(5_000)
            resume.save(item.id, player.currentPosition, player.duration)
        }
    }
    LaunchedEffect(resumedAt) {
        if (resumedAt != null) {
            delay(8_000)
            resumedAt = null
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            resume.save(item.id, player.currentPosition, player.duration, ended = player.playbackState == androidx.media3.common.Player.STATE_ENDED)
            player.release()
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    val prefs = remember { context.getSharedPreferences("player", android.content.Context.MODE_PRIVATE) }
    var showInfo by remember { mutableStateOf(prefs.getBoolean(SHOW_INFO_KEY, false)) }
    var settingsOpen by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    // Whenever the controls hide (or the settings menu closes), give the remote's focus back to the
    // player: if it was on the ⚙ button when that disappeared, nothing had focus anymore and the
    // remote could no longer bring the controls back (only Back worked).
    LaunchedEffect(controlsVisible, settingsOpen) {
        if (!controlsVisible && !settingsOpen) playerView?.requestFocus()
    }
    var snapshot by remember { mutableStateOf<DiagnosticsSnapshot?>(null) }
    LaunchedEffect(player) {
        var previous = SmbStreamStats.snapshot()
        while (true) {
            delay(1000)
            val now = SmbStreamStats.snapshot()
            snapshot = diagnostics.snapshot(player, item.extension, if (isNetwork) previous to now else null)
            previous = now
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        FullScreenDialogWindow()
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { event ->
                    // A key reaching Compose while the controls are hidden means the player lost
                    // focus: hand it back and show the controls instead of swallowing the key.
                    val view = playerView
                    if (view != null && !controlsVisible && !settingsOpen && !view.hasFocus()) {
                        if (event.type == KeyEventType.KeyDown) {
                            view.requestFocus()
                            view.showController()
                        }
                        true
                    } else {
                        false
                    }
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        playerView = this
                        this.player = player
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        setBackgroundColor(android.graphics.Color.BLACK)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        keepScreenOn = true
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { visibility ->
                                controlsVisible = visibility == View.VISIBLE
                            }
                        )
                        // A TV remote's D-pad only reaches the player's own key handling (play/
                        // pause, seek, show controls) if the view can actually take focus.
                        isFocusable = true
                        isFocusableInTouchMode = true
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        post { requestFocus() }
                    }
                }
            )
            val current = snapshot
            if (current != null && (current.error != null || (showInfo && (controlsVisible || current.buffering)))) {
                DiagnosticsPanel(current, showInfo, Modifier.align(Alignment.TopStart).padding(16.dp))
            }
            resumedAt?.let { at ->
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Resumed at ${formatPosition(at)}", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.TextButton(onClick = {
                        player.seekTo(0)
                        resume.clear(item.id)
                        resumedAt = null
                        playerView?.requestFocus()
                    }) { Text("Start over") }
                }
            }
            if (controlsVisible || settingsOpen) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    androidx.compose.material3.IconButton(onClick = { settingsOpen = true }) {
                        androidx.compose.material3.Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Player settings",
                            tint = Color.White
                        )
                    }
                    androidx.compose.material3.DropdownMenu(expanded = settingsOpen, onDismissRequest = { settingsOpen = false }) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Show playback info") },
                            leadingIcon = {
                                androidx.compose.material3.Checkbox(checked = showInfo, onCheckedChange = null)
                            },
                            onClick = {
                                showInfo = !showInfo
                                prefs.edit().putBoolean(SHOW_INFO_KEY, showInfo).apply()
                                settingsOpen = false
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Makes the dialog's own window cover the whole screen, with the status/navigation bars hidden. */
@Composable
private fun FullScreenDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }
}

private const val SHOW_INFO_KEY = "showPlaybackInfo"

private fun formatPosition(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * Where each item was left off, by item id. Not saved in the first 30 s or the last 5% (or once it
 * played to the end) — those start from the beginning next time.
 */
private class ResumePositions(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("resume_positions", android.content.Context.MODE_PRIVATE)

    fun get(id: String): Long? = prefs.getLong(id, -1L).takeIf { it > 0 }

    fun save(id: String, position: Long, duration: Long, ended: Boolean = false) {
        val nearEnd = duration > 0 && position >= duration * 0.95
        if (ended || nearEnd || position < 30_000) clear(id) else prefs.edit().putLong(id, position).apply()
    }

    fun clear(id: String) = prefs.edit().remove(id).apply()
}

/** The error (always), plus the playback info lines when [showInfo] is on. */
@Composable
private fun DiagnosticsPanel(snapshot: DiagnosticsSnapshot, showInfo: Boolean, modifier: Modifier) {
    Column(
        modifier
            .widthIn(max = 720.dp)
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        snapshot.error?.let {
            Text("Playback error", color = Color(0xFFFF8A80), style = MaterialTheme.typography.titleSmall)
            Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
        if (showInfo) snapshot.lines.forEach { line ->
            Text(line, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
}
