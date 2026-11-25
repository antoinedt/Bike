package com.medialibrary.manager.ui.components

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * A diagnostics panel (top left) appears while buffering, on an error, and whenever the playback
 * controls are showing: buffer level, real network speed, codecs and the decoders the device
 * picked (or tracks it can't play at all), dropped frames, and the full error — so a file that
 * won't play says why.
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
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    var controlsVisible by remember { mutableStateOf(true) }
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
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
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
            if (current != null && (controlsVisible || current.buffering || current.error != null)) {
                DiagnosticsPanel(current, Modifier.align(Alignment.TopStart).padding(16.dp))
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

@Composable
private fun DiagnosticsPanel(snapshot: DiagnosticsSnapshot, modifier: Modifier) {
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
        snapshot.lines.forEach { line ->
            Text(line, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
}
