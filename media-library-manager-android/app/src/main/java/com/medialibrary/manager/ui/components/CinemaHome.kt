package com.medialibrary.manager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaKind
import com.medialibrary.manager.playback.ResumeStore

private val Coral = Color(0xFFFF5A4E)
private val EPISODE = Regex("""^(.*?)[\s._-]+S(\d{1,2})[\s._-]?E(\d{1,3})""", RegexOption.IGNORE_CASE)
private val QUALITY_CUT = Regex("""[(\[]?\b(19\d{2}|20\d{2}|2160p|1080p|720p|480p|x26[45]|h ?26[45]|hevc|web|webrip|web-dl|bluray|brrip|hdtv)\b""", RegexOption.IGNORE_CASE)
private val YEAR = Regex("""\b(19\d{2}|20\d{2})\b""")

data class EpisodeInfo(val show: String, val season: Int, val episode: Int)

/** "The.Bear.S05E03.1080p" -> The Bear, 5, 3; null for anything that isn't an episode. */
fun episodeInfo(item: MediaItem): EpisodeInfo? {
    val m = EPISODE.find(item.name) ?: return null
    val show = m.groupValues[1].replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim()
    return if (show.isEmpty()) null else EpisodeInfo(show, m.groupValues[2].toInt(), m.groupValues[3].toInt())
}

private fun cleanTitle(item: MediaItem): String {
    val base = item.name.substringBeforeLast('.').replace(Regex("[._]+"), " ")
    val cut = QUALITY_CUT.find(base)?.range?.first ?: -1
    val title = (if (cut > 0) base.substring(0, cut) else base).trimEnd(' ', '(', '[', '-').trim().ifEmpty { base.trim() }
    // A year at the very start ("1967 Astérix le Gaulois") is the year, not part of the title.
    return title.replace(Regex("""^(19|20)\d{2}\s+(?=\S)"""), "").ifEmpty { title }
}

fun displayTitle(item: MediaItem): String {
    episodeInfo(item)?.let { return "${it.show} · S%02dE%02d".format(it.season, it.episode) }
    return item.title ?: cleanTitle(item)
}

private fun displayYear(item: MediaItem): String? =
    item.year ?: if (episodeInfo(item) != null) null else YEAR.find(item.name)?.value

private fun timeLeft(e: ResumeStore.Entry): String =
    if (e.durationMs > 0) {
        val left = ((e.durationMs - e.positionMs) / 60_000).coerceAtLeast(0)
        if (left >= 60) "${left / 60} h ${left % 60} min left" else "$left min left"
    } else "Stopped at ${e.positionMs / 60_000} min"

private fun placeholder(seed: String): Brush {
    val hue = ((seed.hashCode().toLong() and 0xffffffffL) % 360).toFloat()
    return Brush.verticalGradient(listOf(Color.hsl(hue, 0.32f, 0.30f), Color.hsl((hue + 30f) % 360f, 0.40f, 0.09f)))
}

private data class Rows(
    val continuing: List<Pair<ResumeStore.Entry, MediaItem>>,
    val recent: List<MediaItem>,
    val shows: List<Triple<String, MediaItem, Int>>,
    val movies: List<MediaItem>,
    val music: List<MediaItem>
)

/**
 * One entry per file: the same file found in two places (e.g. a local folder and a share, same
 * name and size) shows once, preferring the local copy.
 */
private fun distinctFiles(items: List<MediaItem>): List<MediaItem> =
    items.groupBy { "${it.name.lowercase()}|${it.sizeBytes}" }
        .values.map { copies -> copies.firstOrNull { it.sourceType == com.medialibrary.manager.model.MediaSourceType.LOCAL } ?: copies.first() }

private fun buildRows(all: List<MediaItem>, resume: List<ResumeStore.Entry>): Rows {
    val byId = all.associateBy { it.id }
    val items = distinctFiles(all)
    val continuing = resume.mapNotNull { e -> byId[e.itemId]?.let { e to it } }
        .distinctBy { (_, item) -> "${item.name.lowercase()}|${item.sizeBytes}" }
    val videos = items.filter { it.kind == MediaKind.VIDEO }
    val recent = videos.sortedByDescending { it.modifiedAtMillis }.take(24)
    val shows = linkedMapOf<String, Triple<String, MediaItem, Int>>()
    val movies = mutableListOf<MediaItem>()
    for (v in videos) {
        val ep = episodeInfo(v)
        if (ep == null) { movies += v; continue }
        val key = ep.show.lowercase()
        val prev = shows[key]
        shows[key] = if (prev == null) Triple(ep.show, v, 1)
        else Triple(prev.first, if (v.modifiedAtMillis > prev.second.modifiedAtMillis) v else prev.second, prev.third + 1)
    }
    return Rows(
        continuing = continuing,
        recent = recent,
        shows = shows.values.sortedByDescending { it.second.modifiedAtMillis },
        movies = movies.sortedBy { displayTitle(it).lowercase() },
        music = items.filter { it.kind == MediaKind.AUDIO }.sortedByDescending { it.modifiedAtMillis }.take(24)
    )
}

/**
 * The Library's home in the Cinema style: a featured title (what you were last watching, or the
 * newest arrival) and rows of posters — Continue watching, Recently added, Shows, Movies, Music.
 * A poster plays (or, for a show, lists its episodes). On a TV the remote lands on the featured
 * title's Play button, and the focused poster grows with a coral outline.
 */
@Composable
fun CinemaHome(
    items: List<MediaItem>,
    resume: List<ResumeStore.Entry>,
    isTv: Boolean,
    onPlay: (MediaItem) -> Unit,
    onShowSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** What's on now on the last channels you watched ("On now" row, after the featured title). */
    nowOn: List<com.medialibrary.manager.tv.TvGuideController.NowOn> = emptyList(),
    tuningChannel: Int? = null,
    onWatchChannel: (com.medialibrary.manager.tv.TvChannel) -> Unit = {},
    onOpenGuide: () -> Unit = {}
) {
    val rows = remember(items, resume) { buildRows(items, resume) }
    val featuredEntry = rows.continuing.firstOrNull()
    val featured = featuredEntry?.second ?: rows.recent.firstOrNull { it.artworkUrl != null } ?: rows.recent.firstOrNull()
    if (featured == null) {
        Text(
            "No videos yet. Add a folder or a network share in Settings; it's scanned automatically.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(24.dp)
        )
        return
    }
    val posterWidth = if (isTv) 150.dp else 112.dp
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(isTv) { if (isTv) runCatching { playFocus.requestFocus() } }

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "hero") {
            Box(
                Modifier.fillMaxWidth().height(if (isTv) 260.dp else 220.dp).clip(RoundedCornerShape(16.dp))
                    .background(placeholder(displayTitle(featured)))
            ) {
                featured.artworkUrl?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(listOf(Color(0xF20B0D12), Color(0xB30B0D12), Color(0x260B0D12)))
                    )
                )
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (featuredEntry != null) "CONTINUE · ${timeLeft(featuredEntry.first).uppercase()}" else "RECENTLY ADDED",
                        color = Coral, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                    )
                    Text(
                        displayTitle(featured), color = Color.White, fontWeight = FontWeight.Bold,
                        fontSize = if (isTv) 30.sp else 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    listOfNotNull(displayYear(featured), featured.extension.uppercase()).joinToString(" · ").let {
                        Text(it, color = Color(0xFFB4BBCB), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = { onPlay(featured) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0B0D12)),
                        modifier = Modifier.focusRequester(playFocus)
                    ) { Text(if (featuredEntry != null) "▶  Resume" else "▶  Play", fontWeight = FontWeight.Bold) }
                }
            }
        }
        if (nowOn.isNotEmpty()) item(key = "now-on") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("On now", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = onOpenGuide) { Text("TV guide ›") }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
                    items(nowOn, key = { it.channel.id }) { on ->
                        ChannelNowCard(on, width = if (isTv) 280.dp else 220.dp, tuning = tuningChannel == on.channel.id) { onWatchChannel(on.channel) }
                    }
                }
            }
        }
        if (rows.continuing.isNotEmpty()) item(key = "continue") {
            PosterRow("Continue watching") {
                items(rows.continuing, key = { it.second.id }) { (entry, item) ->
                    Poster(
                        item, displayTitle(item), timeLeft(entry),
                        progress = if (entry.durationMs > 0) entry.positionMs.toFloat() / entry.durationMs else 0.5f,
                        width = posterWidth
                    ) { onPlay(item) }
                }
            }
        }
        if (rows.recent.isNotEmpty()) item(key = "recent") {
            PosterRow("Recently added") {
                items(rows.recent, key = { it.id }) { item -> Poster(item, displayTitle(item), displayYear(item), width = posterWidth) { onPlay(item) } }
            }
        }
        if (rows.shows.isNotEmpty()) item(key = "shows") {
            PosterRow("Shows") {
                items(rows.shows, key = { it.first }) { (show, item, count) ->
                    Poster(item, show, "Show · $count episode${if (count == 1) "" else "s"}", width = posterWidth, group = count) { onShowSearch(show) }
                }
            }
        }
        if (rows.movies.isNotEmpty()) item(key = "movies") {
            PosterRow("Movies") {
                items(rows.movies, key = { it.id }) { item -> Poster(item, displayTitle(item), displayYear(item), width = posterWidth) { onPlay(item) } }
            }
        }
        if (rows.music.isNotEmpty()) item(key = "music") {
            PosterRow("Music") {
                items(rows.music, key = { it.id }) { item -> Poster(item, displayTitle(item), null, width = posterWidth) { onPlay(item) } }
            }
        }
    }
}

@Composable
private fun PosterRow(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp), content = content)
    }
}

@Composable
private fun Poster(
    item: MediaItem,
    label: String,
    sub: String?,
    progress: Float? = null,
    width: androidx.compose.ui.unit.Dp,
    /** A show (opens a list of episodes) rather than a file (plays): drawn as a stack with a badge. */
    group: Int? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(width)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        if (group == null) Spacer(Modifier.height(8.dp))
        if (group != null) {
            // Two card edges peeking above: "there's more than one in here".
            Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(Color(0x33FFFFFF)))
            Box(Modifier.padding(horizontal = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(Color(0x55FFFFFF)))
        }
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .scale(if (focused) 1.06f else 1f)
                .clip(RoundedCornerShape(10.dp))
                .background(placeholder(label))
                .then(if (focused) Modifier.border(3.dp, Coral, RoundedCornerShape(10.dp)) else Modifier)
        ) {
            if (item.artworkUrl != null) {
                AsyncImage(model = item.artworkUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 16.sp,
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 18.dp)
                )
            }
            // Top-right badge: ▶ plays this file; 📂 n opens a show's episodes.
            Text(
                if (group != null) "📂 $group" else "▶",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 3.dp)
            )
            progress?.let { p ->
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 8.dp)
                        .fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x47FFFFFF))
                ) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0.03f, 1f)).height(4.dp).background(Coral))
                }
            }
        }
        if (item.artworkUrl != null) {
            Text(
                label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
            )
        }
        sub?.let {
            Text(
                it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.height(0.dp))
    }
}

/** A live channel: the show's picture (or the channel logo), "LIVE", what's on and how far into it. */
@Composable
private fun ChannelNowCard(
    on: com.medialibrary.manager.tv.TvGuideController.NowOn,
    width: androidx.compose.ui.unit.Dp,
    tuning: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val program = on.program
    val now = System.currentTimeMillis()
    val progress = program?.let { ((now - it.start).toFloat() / (it.end - it.start).coerceAtLeast(1)).coerceIn(0f, 1f) }
    Column(Modifier.width(width).onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .scale(if (focused) 1.04f else 1f)
                .clip(RoundedCornerShape(10.dp))
                .background(placeholder(on.channel.name))
                .then(if (focused) Modifier.border(3.dp, Coral, RoundedCornerShape(10.dp)) else Modifier)
        ) {
            val picture = program?.imageUrl ?: on.channel.logoUrl
            if (picture != null) {
                AsyncImage(
                    model = picture, contentDescription = null,
                    contentScale = if (program?.imageUrl != null) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(if (program?.imageUrl != null) 0.dp else 24.dp)
                )
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60B0D12)))))
            Text(
                if (tuning) "Opening…" else "● LIVE",
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .clip(RoundedCornerShape(50)).background(Coral).padding(horizontal = 7.dp, vertical = 2.dp)
            )
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(
                    program?.title ?: on.channel.name, color = Color.White, fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                progress?.let { p ->
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x47FFFFFF))) {
                        Box(Modifier.fillMaxWidth(p.coerceAtLeast(0.03f)).height(3.dp).background(Coral))
                    }
                }
            }
        }
        Text(
            listOfNotNull(on.channel.number?.toString(), on.channel.name).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, modifier = Modifier.padding(top = 4.dp)
        )
    }
}
