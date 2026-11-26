package com.medialibrary.manager.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.medialibrary.manager.di.ServiceLocator
import com.medialibrary.manager.tv.BrowserSignIn
import com.medialibrary.manager.tv.TvChannel
import com.medialibrary.manager.tv.TvGuideController
import com.medialibrary.manager.tv.TvGuideState
import com.medialibrary.manager.tv.TvProgram
import com.medialibrary.manager.ui.theme.AccentBlue
import com.medialibrary.manager.ui.theme.DangerRed
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** The TV tab: the Fizz TV guide for the channels in your plan. Asks you to sign in the first time. */
@Composable
fun TvScreen(isTv: Boolean) {
    val controller = ServiceLocator.tvGuide
    val signedIn by controller.signedIn.collectAsState()
    LaunchedEffect(Unit) { controller.open() }
    when (signedIn) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        false -> TvSignIn(controller, isTv)
        true -> TvGuide(controller, isTv)
    }
}

// ---------------- Sign-in ----------------

@Composable
private fun TvSignIn(controller: TvGuideController, isTv: Boolean) {
    val state by controller.signIn.collectAsState()
    var browser by remember { mutableStateOf<BrowserSignIn?>(null) }

    browser?.let { pending ->
        SignInWebView(
            pending = pending,
            isRedirect = controller.client::isRedirect,
            onRedirect = { url ->
                browser = null
                controller.finishBrowserSignIn(url, pending)
            },
            onDismiss = { browser = null }
        )
    }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Fizz TV", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Sign in with your Fizz account to see the guide for the channels in your plan. You only need to do this once.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.error?.let { Text(it, color = DangerRed, style = MaterialTheme.typography.bodyMedium) }
            val code = state.deviceCode
            when {
                state.busy -> CircularProgressIndicator()
                code != null -> {
                    Text("On your phone or computer, go to", style = MaterialTheme.typography.bodyMedium)
                    Text(code.verificationUri.removePrefix("https://"), style = MaterialTheme.typography.titleLarge, color = AccentBlue)
                    Text("and enter this code:", style = MaterialTheme.typography.bodyMedium)
                    Text(code.userCode, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Waiting for you to sign in…", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = controller::cancelDeviceSignIn) { Text("Cancel") }
                }
                isTv -> {
                    // A remote makes typing a password painful: sign in on your phone with a code instead.
                    Button(onClick = controller::startDeviceSignIn) { Text("Sign in with a code") }
                    OutlinedButton(onClick = { browser = controller.startBrowserSignIn() }) { Text("Sign in on this TV") }
                }
                else -> {
                    Button(onClick = { browser = controller.startBrowserSignIn() }) { Text("Sign in") }
                    TextButton(onClick = controller::startDeviceSignIn) { Text("Sign in with a code from another device") }
                }
            }
        }
    }
}

/** Fizz's own sign-in page; it ends on the fizztv:// address, which is caught here instead of opening the Fizz app. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SignInWebView(pending: BrowserSignIn, isRedirect: (String) -> Boolean, onRedirect: (String) -> Unit, onDismiss: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var done by remember { mutableStateOf(false) }
    val finish: (String) -> Boolean = { url ->
        if (isRedirect(url)) {
            if (!done) {
                done = true
                onRedirect(url)
            }
            true
        } else {
            false
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sign in to Fizz", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                    finish(request.url.toString())

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    if (finish(url)) view.stopLoading() else loading = true
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    loading = false
                                }
                            }
                            loadUrl(pending.url)
                        }
                    }
                )
            }
        }
    }
}

// ---------------- Guide ----------------

private const val HALF_HOUR = 30 * 60_000L

@Composable
private fun TvGuide(controller: TvGuideController, isTv: Boolean) {
    val state by controller.guide.collectAsState()
    var selected by remember { mutableStateOf<Pair<TvProgram, TvChannel>?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    selected?.let { (program, channel) -> ProgramDialog(program, channel) { selected = null } }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of Fizz?") },
            text = { Text("You'll need to sign in again to see the guide.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; controller.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } }
        )
    }

    // Bumped to make the grid jump back to the current time.
    var jumpToNow by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("My plan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                state.email?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
            TextButton(onClick = {
                if (state.dayOffset != 0) controller.selectDay(0)
                jumpToNow++
            }) { Text("Now") }
            TextButton(onClick = controller::reload) { Text("Refresh") }
            TextButton(onClick = { confirmSignOut = true }) { Text("Sign out") }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (offset in -1..6) {
                FilterChip(selected = state.dayOffset == offset, onClick = { controller.selectDay(offset) }, label = { Text(dayLabel(offset)) })
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        state.error?.let { message ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, color = DangerRed, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = controller::reload) { Text("Try again") }
            }
        }
        if (state.channels.isEmpty()) {
            if (!state.loading && state.error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No channels in your plan.") }
            }
        } else {
            GuideGrid(state, isTv, now, jumpToNow) { program, channel -> selected = program to channel }
        }
    }
}

@Composable
private fun GuideGrid(state: TvGuideState, isTv: Boolean, now: Long, jumpToNow: Int, onOpen: (TvProgram, TvChannel) -> Unit) {
    val density = LocalDensity.current
    val dpPerMinute = if (isTv) 5f else 3f
    val pxPerMs = with(density) { dpPerMinute.dp.toPx() } / 60_000f
    val channelWidth = if (isTv) 128.dp else 76.dp
    val rowHeight = if (isTv) 64.dp else 58.dp
    val dayStart = state.dayStart
    val totalPx = TvGuideController.DAY_MS * pxPerMs
    var viewport by remember { mutableFloatStateOf(0f) }
    var scrollX by remember { mutableFloatStateOf(0f) }
    val maxScroll = { max(0f, totalPx - viewport) }
    val scrollState = rememberScrollableState { delta ->
        val old = scrollX
        scrollX = (old - delta).coerceIn(0f, maxScroll())
        old - scrollX
    }

    // Start at "now" today (with half an hour of context), or the same time of day on other days.
    LaunchedEffect(dayStart, jumpToNow, viewport > 0) {
        if (viewport <= 0) return@LaunchedEffect
        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val target = (System.currentTimeMillis() - today - HALF_HOUR).coerceAtLeast(0)
        scrollX = (target * pxPerMs).coerceIn(0f, maxScroll())
    }

    val ensureVisible: (Float, Float) -> Unit = { startPx, endPx ->
        val margin = with(density) { 24.dp.toPx() }
        if (startPx < scrollX + margin) {
            scrollX = (startPx - margin).coerceIn(0f, maxScroll())
        } else if (startPx > scrollX + viewport * 0.6f) {
            scrollX = (startPx - margin).coerceIn(0f, maxScroll())
        } else if (endPx > scrollX + viewport && endPx - startPx < viewport) {
            scrollX = (endPx - viewport + margin).coerceIn(0f, maxScroll())
        }
    }

    Column(Modifier.fillMaxSize().scrollable(scrollState, Orientation.Horizontal)) {
        // Time header.
        Row(Modifier.fillMaxWidth().height(28.dp)) {
            Spacer(Modifier.width(channelWidth))
            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().onSizeChanged { viewport = it.width.toFloat() }) {
                val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
                for (slot in 0 until 48) {
                    val x = slot * HALF_HOUR * pxPerMs
                    Text(
                        format.format(Date(dayStart + slot * HALF_HOUR)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.offset { IntOffset((x - scrollX).roundToInt() + 6, 6) }
                    )
                }
                NowLine(state, now, pxPerMs) { scrollX }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.channels, key = { it.id }) { channel ->
                Row(Modifier.fillMaxWidth().height(rowHeight).padding(vertical = 1.dp)) {
                    ChannelCell(channel, Modifier.width(channelWidth).fillMaxHeight())
                    Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                        val programs = state.programs[channel.id]
                        if (programs == null) {
                            Box(Modifier.fillMaxSize().padding(1.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(4.dp)))
                        } else if (programs.isEmpty()) {
                            Text("No listings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                        }
                        programs?.forEach { program ->
                            val startPx = max(0L, program.start - dayStart) * pxPerMs
                            val endPx = (program.end - dayStart).coerceAtMost(TvGuideController.DAY_MS) * pxPerMs
                            if (endPx > startPx) {
                                ProgramCell(
                                    program = program,
                                    now = now,
                                    width = with(density) { (endPx - startPx).toDp() },
                                    startPx = startPx,
                                    scrollX = { scrollX },
                                    onFocused = { ensureVisible(startPx, endPx) },
                                    onClick = { onOpen(program, channel) }
                                )
                            }
                        }
                        NowLine(state, now, pxPerMs) { scrollX }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowLine(state: TvGuideState, now: Long, pxPerMs: Float, scrollX: () -> Float) {
    if (now < state.dayStart || now >= state.dayStart + TvGuideController.DAY_MS) return
    val x = (now - state.dayStart) * pxPerMs
    Box(
        Modifier.offset { IntOffset((x - scrollX()).roundToInt(), 0) }
            .width(2.dp).fillMaxHeight().background(DangerRed)
    )
}

@Composable
private fun ChannelCell(channel: TvChannel, modifier: Modifier) {
    Row(modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (channel.logoUrl != null) {
                AsyncImage(model = channel.logoUrl, contentDescription = channel.name, modifier = Modifier.height(26.dp).fillMaxWidth())
            } else {
                Text(channel.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            channel.number?.let { Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ProgramCell(
    program: TvProgram,
    now: Long,
    width: androidx.compose.ui.unit.Dp,
    startPx: Float,
    scrollX: () -> Float,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val live = now in program.start until program.end
    val past = program.end <= now
    val background = when {
        live -> AccentBlue.copy(alpha = 0.28f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (past) 0.35f else 0.6f)
    }
    val density = LocalDensity.current
    val widthPx = with(density) { width.toPx() }
    Box(
        Modifier
            .offset { IntOffset((startPx - scrollX()).roundToInt(), 0) }
            .width(width)
            .fillMaxHeight()
            .padding(1.dp)
            .background(background, RoundedCornerShape(4.dp))
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(4.dp)) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(onClick = onClick)
    ) {
        // Keep the title readable when the show started before the left edge of the screen.
        val inset = with(density) { 60.dp.toPx() }
        Column(
            Modifier
                .offset { IntOffset((scrollX() - startPx).coerceIn(0f, max(0f, widthPx - inset)).roundToInt(), 0) }
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                program.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                color = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                program.episode ?: timeRange(program),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProgramDialog(program: TvProgram, channel: TvChannel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(program.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                program.imageUrl?.let {
                    AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                }
                program.episode?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                Text(
                    "${channel.number?.let { "$it · " } ?: ""}${channel.name} · ${dayName(program.start)} ${timeRange(program)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                program.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun timeRange(program: TvProgram): String {
    val f = DateFormat.getTimeInstance(DateFormat.SHORT)
    return "${f.format(Date(program.start))} – ${f.format(Date(program.end))}"
}

private fun dayName(epochMs: Long): String {
    val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val offset = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), date).toInt()
    return dayLabel(offset)
}

private fun dayLabel(offset: Int): String = when (offset) {
    0 -> "Today"
    -1 -> "Yesterday"
    1 -> "Tomorrow"
    else -> LocalDate.now().plusDays(offset.toLong()).let {
        "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${it.dayOfMonth}"
    }
}
