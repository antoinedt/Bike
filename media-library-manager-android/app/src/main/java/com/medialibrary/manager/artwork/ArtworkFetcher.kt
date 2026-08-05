package com.medialibrary.manager.artwork

import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaKind
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

    cleaned = cleaned.replace(Regex("[-([{].*$"), "").trim()
    return GuessedTitle(cleaned.ifBlank { withoutExt }, year)
}

@Serializable
private data class ITunesResult(val artworkUrl100: String? = null)

@Serializable
private data class ITunesResponse(val results: List<ITunesResult> = emptyList())

private fun upsizeArtwork(url: String): String = url.replace(Regex("\\d+x\\d+bb(\\.\\w+)$"), "600x600bb$1")

/**
 * Looks up artwork via the iTunes Search API (Apple's public, keyless catalog search — same
 * approach as the desktop app's artworkFetcher.ts, not scraping or requiring an API key).
 */
class ArtworkFetcher(private val httpClient: OkHttpClient = OkHttpClient()) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchArtworkUrl(item: MediaItem): String? {
        val (title, year) = guessTitle(item.name)
        if (title.isBlank()) return null
        return when (item.kind) {
            MediaKind.VIDEO -> lookup(if (year != null) "$title $year" else title, "movie")
            MediaKind.AUDIO -> lookup(title, "song")
            else -> null
        }
    }

    private suspend fun lookup(term: String, entity: String): String? = withContext(Dispatchers.IO) {
        val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(term, "UTF-8")}&entity=$entity&limit=1"
        val request = Request.Builder().url(url).build()
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val parsed = json.decodeFromString<ITunesResponse>(body)
                parsed.results.firstOrNull()?.artworkUrl100?.let(::upsizeArtwork)
            }
        }.getOrNull()
    }

    /** Fetches artwork for every item missing it (video/audio only), bounded concurrency. Returns id -> url. */
    suspend fun fetchForLibrary(items: List<MediaItem>): Map<String, String> = coroutineScope {
        val candidates = items.filter {
            it.artworkUrl == null && (it.kind == MediaKind.VIDEO || it.kind == MediaKind.AUDIO)
        }
        val semaphore = Semaphore(CONCURRENCY)
        candidates
            .map { item -> async { semaphore.withPermit { item.id to fetchArtworkUrl(item) } } }
            .map { it.await() }
            .mapNotNull { (id, url) -> url?.let { id to it } }
            .toMap()
    }
}
