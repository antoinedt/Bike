package com.medialibrary.manager.artwork

import android.content.Context
import android.net.Uri
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaKind
import com.medialibrary.manager.model.MediaMetadata
import com.medialibrary.manager.model.MediaSourceType
import com.medialibrary.manager.model.NetworkShare
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

    private suspend fun lookupItunes(term: String, entity: String): ITunesResult? = withContext(Dispatchers.IO) {
        val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(term, "UTF-8")}&entity=$entity&limit=1"
        val request = Request.Builder().url(url).build()
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                json.decodeFromString<ITunesResponse>(body).results.firstOrNull()
            }
        }.getOrNull()
    }

    private suspend fun fetchMetadata(item: MediaItem): MediaMetadata? {
        val (guessedTitle, guessedYear) = guessTitle(item.name)
        val term = if (guessedYear != null) "$guessedTitle $guessedYear" else guessedTitle
        val result = when (item.kind) {
            MediaKind.VIDEO -> lookupItunes(term, "movie")
            MediaKind.AUDIO -> lookupItunes(guessedTitle, "song")
            else -> null
        }

        var artworkUrl = result?.artworkUrl100?.let(::upsizeArtwork)
        if (artworkUrl == null && item.kind == MediaKind.VIDEO && item.sourceType == MediaSourceType.LOCAL) {
            artworkUrl = VideoThumbnailExtractor.extract(appContext, Uri.parse(item.path))
        }

        val title = (result?.trackName ?: result?.collectionName ?: guessedTitle).ifBlank { null }
        val year = result?.releaseDate?.take(4) ?: guessedYear
        if (title == null && year == null && artworkUrl == null) return null
        return MediaMetadata(title = title, year = year, artworkUrl = artworkUrl)
    }

    /**
     * Fetches title/year/artwork for every video/audio item missing them, local and network.
     * Network items check for an existing sidecar on the share before fetching; a fresh fetch is
     * written back as one. Returns id -> metadata for everything found.
     */
    suspend fun fetchForLibrary(items: List<MediaItem>, networkShares: List<NetworkShare>): Map<String, MediaMetadata> =
        coroutineScope {
            val candidates = items.filter {
                it.title == null && (it.kind == MediaKind.VIDEO || it.kind == MediaKind.AUDIO)
            }
            val semaphore = Semaphore(CONCURRENCY)
            candidates
                .map { item -> async { semaphore.withPermit { runCatching { item.id to resolve(item, networkShares) }.getOrNull() } } }
                .mapNotNull { it.await() }
                .mapNotNull { (id, metadata) -> metadata?.let { id to it } }
                .toMap()
        }

    private suspend fun resolve(item: MediaItem, networkShares: List<NetworkShare>): MediaMetadata? {
        val share = if (item.sourceType == MediaSourceType.NETWORK) {
            item.shareId?.let { shareId -> networkShares.find { it.id == shareId } }
        } else {
            null
        }
        val relPath = share?.let { item.path.removePrefix("smb://${it.id}/") }

        // A sidecar with a title is a fully-resolved result from a previous fetch — use it as-is.
        // One with only tags (e.g. the user tagged this item before ever running a fetch on it)
        // isn't: fall through to a real fetch below, carrying the existing tags forward so they
        // aren't lost under the fresh write.
        val cached = if (share != null && relPath != null) SmbSidecar.read(share, relPath) else null
        if (cached?.title != null) return cached

        val fetched = fetchMetadata(item) ?: return null
        val metadata = if (!cached?.tags.isNullOrEmpty()) fetched.copy(tags = cached!!.tags) else fetched
        if (share != null && relPath != null) {
            runCatching { SmbSidecar.write(share, relPath, metadata) }
        }
        return metadata
    }
}
