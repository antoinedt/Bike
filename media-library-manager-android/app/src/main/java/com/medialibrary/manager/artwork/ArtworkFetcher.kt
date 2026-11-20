package com.medialibrary.manager.artwork

import android.content.Context
import android.net.Uri
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaKind
import com.medialibrary.manager.model.MediaMetadata
import com.medialibrary.manager.model.MediaSourceType
import com.medialibrary.manager.model.NetworkShare
import com.medialibrary.manager.model.ScanLogEntry
import com.medialibrary.manager.model.ScanProgressEvent
import com.medialibrary.manager.network.SmbSidecar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

private const val CONCURRENCY = 4

private val NOISE_MARKERS = Regex(
    "\\b(1080p|2160p|720p|480p|4k|uhd|hdr|bluray|blu-ray|bdrip|brrip|webrip|web-dl|webdl|hdtv|dvdrip|remux|repack|proper|x264|x265|h264|h265|hevc|aac|dts|ac3|5\\.1)\\b",
    RegexOption.IGNORE_CASE
)
private val YEAR_MARKER = Regex("\\b(19|20)\\d{2}\\b")

data class GuessedTitle(val title: String, val year: String?)

/**
 * Guesses a clean search title from a scene-style filename — a Kotlin port of the desktop
 * app's guessTitle, e.g. "The.Movie.Name.2019.1080p.BluRay.x264-GROUP.mkv" -> "The Movie Name" / "2019".
 */
fun guessTitle(filename: String): GuessedTitle {
    val withoutExt = filename.substringBeforeLast('.', filename)
    var cleaned = withoutExt.replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim()

    val noiseMatch = NOISE_MARKERS.find(cleaned)
    if (noiseMatch != null) cleaned = cleaned.substring(0, noiseMatch.range.first).trim()

    val yearMatch = YEAR_MARKER.find(cleaned)
    var year: String? = null
    if (yearMatch != null) {
        year = yearMatch.value
        cleaned = cleaned.substring(0, yearMatch.range.first).trim()
    }

    // Unlike JS, Kotlin/Java regex treats an unescaped '[' inside a character class as starting a
    // nested class, so the '[' here must be escaped to be matched literally alongside -, (, {.
    cleaned = cleaned.replace(Regex("[-(\\[{].*$"), "").trim()
    return GuessedTitle(cleaned.ifBlank { withoutExt }, year)
}

@Serializable
private data class ITunesResult(
    val artworkUrl100: String? = null,
    val trackName: String? = null,
    val collectionName: String? = null,
    val releaseDate: String? = null
)

@Serializable
private data class ITunesResponse(val results: List<ITunesResult> = emptyList())

private fun upsizeArtwork(url: String): String = url.replace(Regex("\\d+x\\d+bb(\\.\\w+)$"), "600x600bb$1")

// A real browser UA, not this app's own — Google blocks/CAPTCHAs a plainly automated UA far more
// aggressively than one that looks like an ordinary browser doing the same search a person would
// do by hand.
private const val GOOGLE_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
private val GOOGLE_IMAGE_MARKER = Regex("\"ou\":\"(https?:\\\\?/\\\\?/[^\"]+?)\"")
private val GOOGLE_YEAR_MARKER = Regex("\\((19|20)\\d{2}\\)")

data class GoogleSearchMetadata(val artworkUrl: String? = null, val year: String? = null)

/**
 * Looks up title/year/artwork via the iTunes Search API (keyless, no scraping), falling back to
 * a frame pulled from the video itself — via VideoThumbnailExtractor, local items only — when
 * iTunes has nothing, which covers most of a home library (camera footage, home videos) that no
 * commercial catalog would ever know about.
 *
 * Persists results two ways: every item's fetched metadata is merged into ItemCacheRepository by
 * the caller (so it survives app restarts and rescans on this device), and for network items this
 * also checks for / writes a ".metadata.json" sidecar on the share itself via SmbSidecar, so any
 * other device browsing the same share sees it too, without re-fetching.
 */
class ArtworkFetcher(
    private val appContext: Context,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Real failures (network error, bad HTTP status, unparseable body) are thrown rather than
    // swallowed here, so the caller can tell "looked and found nothing" (a null return — a
    // legitimate, logged "not-found") apart from "the lookup itself broke" (a logged "error" with
    // the real reason) — this is the "why" behind the fetch-progress dialog's per-item log.
    private suspend fun lookupItunes(term: String, entity: String): ITunesResult? = withContext(Dispatchers.IO) {
        val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(term, "UTF-8")}&entity=$entity&limit=1"
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty response body")
            json.decodeFromString<ITunesResponse>(body).results.firstOrNull()
        }
    }

    /**
     * Falls back to a plain Google search for the guessed title (iTunes only knows its own
     * commercial catalog, which plenty of a legally-obtained library — less common movies, most
     * songs, anything iTunes just doesn't carry — will never match): takes the first image
     * result as artwork, and a parenthesized "(19xx)"/"(20xx)" on the page as the release year,
     * if either is present. Scrapes Google's search-results HTML for an "ou" ("original url")
     * field Google embeds server-side for each image result — a long-standing, widely-used but
     * unofficial trick (no API key, no official support) that will simply stop matching, not
     * throw, if Google changes its markup.
     */
    private suspend fun searchGoogle(query: String): GoogleSearchMetadata = withContext(Dispatchers.IO) {
        val url = "https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}&hl=en"
        val request = Request.Builder().url(url)
            .header("User-Agent", GOOGLE_USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val html = response.body?.string() ?: throw IOException("Empty response body")
            val artworkUrl = GOOGLE_IMAGE_MARKER.find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            val year = GOOGLE_YEAR_MARKER.find(html)?.value?.trim('(', ')')
            GoogleSearchMetadata(artworkUrl, year)
        }
    }

    private suspend fun fetchMetadata(item: MediaItem, log: MutableList<ScanLogEntry>): MediaMetadata {
        val (guessedTitle, guessedYear) = guessTitle(item.name)
        val term = if (guessedYear != null) "$guessedTitle $guessedYear" else guessedTitle

        var result: ITunesResult? = null
        try {
            result = when (item.kind) {
                MediaKind.VIDEO -> lookupItunes(term, "movie")
                MediaKind.AUDIO -> lookupItunes(guessedTitle, "song")
                else -> null
            }
            log += ScanLogEntry("iTunes", if (result != null) "found" else "not-found")
        } catch (e: Exception) {
            log += ScanLogEntry("iTunes", "error", e.message)
        }

        var artworkUrl = result?.artworkUrl100?.let(::upsizeArtwork)
        var title = (result?.trackName ?: result?.collectionName ?: guessedTitle).ifBlank { null }
        var year = result?.releaseDate?.take(4) ?: guessedYear

        // iTunes only knows its own commercial catalog — plenty of a legally-obtained library
        // (less common movies, most songs, home videos) will never match there. Fall back to a
        // plain Google search for whatever we're missing before giving up on it.
        if (artworkUrl == null || year == null) {
            val query = if (year != null) "$title $year" else title ?: guessedTitle
            try {
                val google = searchGoogle(query)
                log += ScanLogEntry("Google search", if (google.artworkUrl != null || google.year != null) "found" else "not-found")
                if (artworkUrl == null) artworkUrl = google.artworkUrl
                if (year == null) year = google.year
            } catch (e: Exception) {
                log += ScanLogEntry("Google search", "error", e.message)
            }
        }

        // Still nothing? For a local video, a real frame pulled from the file itself works for
        // literally anything with a video track, no external lookup required — the last resort.
        // (Network video items don't get this fallback yet — VideoThumbnailExtractor needs a
        // Context+Uri or raw file descriptor, neither of which an SMB share offers without a
        // custom MediaDataSource — so that's logged explicitly rather than left unexplained.)
        if (artworkUrl == null && item.kind == MediaKind.VIDEO) {
            if (item.sourceType == MediaSourceType.LOCAL) {
                try {
                    val thumb = VideoThumbnailExtractor.extract(appContext, Uri.parse(item.path))
                    log += ScanLogEntry("Video frame", if (thumb != null) "found" else "not-found")
                    artworkUrl = thumb
                } catch (e: Exception) {
                    log += ScanLogEntry("Video frame", "error", e.message)
                }
            } else {
                log += ScanLogEntry("Video frame", "not-found", "not supported for network items yet")
            }
        }

        return MediaMetadata(title = title, year = year, artworkUrl = artworkUrl, infoFetched = true)
    }

    /**
     * Fetches title/year/artwork for every video/audio item not yet checked (win or lose — see
     * MediaItem.infoFetched) in `items` — the caller decides the scope, e.g. just the folder the
     * user is currently browsing. Network items check for an existing sidecar on the share before
     * fetching; every attempt, success or total failure, is written back as one (marked
     * infoFetched) so it's there next time and a failed item isn't silently retried forever. Pass
     * `force: true` to re-fetch every item in `items` regardless of infoFetched or any cached
     * sidecar, overriding whatever was found before — the user's manual override for a stuck "no
     * match" result. `isCancelled`, if given, is checked before starting each new item — once it
     * returns true (the user's Cancel button), no further items are started, but whatever was
     * already found for earlier ones is kept, not thrown away (bounded by CONCURRENCY, so at most
     * that many items already in flight finish naturally). `onProgress`, if given, is called with
     * a live event per item (started, then done/cached/error) — this is what drives the progress
     * dialog's log, including the per-step reason an item came up with nothing. Returns id ->
     * metadata for everything attempted.
     */
    suspend fun fetchForLibrary(
        items: List<MediaItem>,
        networkShares: List<NetworkShare>,
        force: Boolean = false,
        isCancelled: () -> Boolean = { false },
        onProgress: ((ScanProgressEvent) -> Unit)? = null
    ): Map<String, MediaMetadata> =
        coroutineScope {
            val candidates = items.filter {
                (force || !it.infoFetched) && (it.kind == MediaKind.VIDEO || it.kind == MediaKind.AUDIO)
            }
            val semaphore = Semaphore(CONCURRENCY)
            candidates
                .map { item ->
                    async {
                        semaphore.withPermit {
                            if (isCancelled()) return@withPermit null
                            onProgress?.invoke(ScanProgressEvent(item.id, item.name, "started"))
                            runCatching { item.id to resolve(item, networkShares, force, onProgress) }
                                .onFailure {
                                    onProgress?.invoke(ScanProgressEvent(item.id, item.name, "error", error = it.message))
                                }
                                .getOrNull()
                        }
                    }
                }
                .mapNotNull { it.await() }
                .toMap()
        }

    private suspend fun resolve(
        item: MediaItem,
        networkShares: List<NetworkShare>,
        force: Boolean,
        onProgress: ((ScanProgressEvent) -> Unit)?
    ): MediaMetadata {
        val share = if (item.sourceType == MediaSourceType.NETWORK) {
            item.shareId?.let { shareId -> networkShares.find { it.id == shareId } }
        } else {
            null
        }
        val relPath = share?.let { item.path.removePrefix("smb://${it.id}/") }

        // A sidecar already marked infoFetched is a fully-resolved result from a previous attempt
        // (on this device or another) — use it as-is, success or not, unless the caller asked to
        // force a fresh fetch anyway. One with only tags (e.g. the user tagged this item before
        // ever running a fetch on it) isn't resolved: fall through to a real fetch below, carrying
        // the existing tags forward so they aren't lost.
        val cached = if (share != null && relPath != null) SmbSidecar.read(share, relPath) else null
        if (!force && cached?.infoFetched == true) {
            onProgress?.invoke(ScanProgressEvent(item.id, item.name, "cached"))
            return cached
        }

        val log = mutableListOf<ScanLogEntry>()
        val fetched = fetchMetadata(item, log)
        val metadata = if (!cached?.tags.isNullOrEmpty()) fetched.copy(tags = cached!!.tags) else fetched
        if (share != null && relPath != null) {
            runCatching { SmbSidecar.write(share, relPath, metadata) }
        }
        onProgress?.invoke(ScanProgressEvent(item.id, item.name, "done", log = log))
        return metadata
    }
}
