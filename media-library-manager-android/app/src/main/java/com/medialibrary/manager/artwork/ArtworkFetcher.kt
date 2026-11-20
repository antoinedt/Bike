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

private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

@Serializable
private data class TmdbMovieResult(val poster_path: String? = null, val release_date: String? = null)

@Serializable
private data class TmdbTvResult(val poster_path: String? = null, val first_air_date: String? = null)

@Serializable
private data class TmdbMovieSearchResponse(val results: List<TmdbMovieResult> = emptyList())

@Serializable
private data class TmdbTvSearchResponse(val results: List<TmdbTvResult> = emptyList())

data class TmdbMetadata(val artworkUrl: String? = null, val year: String? = null)

// MusicBrainz's usage etiquette asks for an app name + contact/homepage in the User-Agent, not a
// browser-impersonating one — unlike TMDb or iTunes, it has no API key to identify the caller by.
private const val MUSICBRAINZ_USER_AGENT = "MediaLibraryManager/0.1 (+https://github.com/antoinedt/Bike)"

@Serializable
private data class MusicBrainzRelease(val id: String)

@Serializable
private data class MusicBrainzSearchResponse(val releases: List<MusicBrainzRelease> = emptyList())

data class AlbumArtMetadata(val artworkUrl: String? = null)

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
     * Falls back to TMDb (The Movie Database) — a free, official, keyless-to-browse-but-key-to-
     * query API for movie/TV posters and release years, for whatever iTunes' commercial catalog
     * has nothing for (less common movies, most TV, home videos iTunes will never know). Requires
     * a free API key from themoviedb.org/settings/api, set in Settings; returns an empty result
     * (not an error) when no key is configured, so this fallback is simply skipped rather than
     * failing every item. Searches movies first, then TV (a scene-style filename doesn't say
     * which it is).
     */
    private suspend fun fetchArtworkFromTmdb(apiKey: String, query: String, year: String?): TmdbMetadata {
        if (apiKey.isBlank()) return TmdbMetadata()
        val yearParam = year?.let { "&year=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        val movie = withContext(Dispatchers.IO) {
            val url = "https://api.themoviedb.org/3/search/movie?api_key=${URLEncoder.encode(apiKey, "UTF-8")}" +
                "&query=${URLEncoder.encode(query, "UTF-8")}$yearParam"
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty response body")
                json.decodeFromString<TmdbMovieSearchResponse>(body).results.firstOrNull()
            }
        }
        if (movie != null) {
            return TmdbMetadata(
                artworkUrl = movie.poster_path?.let { "$TMDB_IMAGE_BASE$it" },
                year = movie.release_date?.take(4)
            )
        }

        val tvYearParam = year?.let { "&first_air_date_year=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        val tv = withContext(Dispatchers.IO) {
            val url = "https://api.themoviedb.org/3/search/tv?api_key=${URLEncoder.encode(apiKey, "UTF-8")}" +
                "&query=${URLEncoder.encode(query, "UTF-8")}$tvYearParam"
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty response body")
                json.decodeFromString<TmdbTvSearchResponse>(body).results.firstOrNull()
            }
        }
        return if (tv != null) {
            TmdbMetadata(artworkUrl = tv.poster_path?.let { "$TMDB_IMAGE_BASE$it" }, year = tv.first_air_date?.take(4))
        } else {
            TmdbMetadata()
        }
    }

    /**
     * Falls back to MusicBrainz (release search) + the Cover Art Archive (front-cover image) for
     * audio items iTunes has nothing for — both free, official, keyless APIs, no API key or
     * scraping involved. Confirms the Cover Art Archive actually has an image for the matched
     * release before handing back its URL, since plenty of MusicBrainz releases have no cover art
     * uploaded.
     */
    private suspend fun fetchAlbumArtFromMusicBrainz(query: String): AlbumArtMetadata = withContext(Dispatchers.IO) {
        val searchUrl = "https://musicbrainz.org/ws/2/release/?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=1"
        val searchRequest = Request.Builder().url(searchUrl).header("User-Agent", MUSICBRAINZ_USER_AGENT).build()
        val release = httpClient.newCall(searchRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty response body")
            json.decodeFromString<MusicBrainzSearchResponse>(body).releases.firstOrNull()
        } ?: return@withContext AlbumArtMetadata()

        val artUrl = "https://coverartarchive.org/release/${release.id}/front"
        val artRequest = Request.Builder().url(artUrl).head().header("User-Agent", MUSICBRAINZ_USER_AGENT).build()
        httpClient.newCall(artRequest).execute().use { response ->
            if (!response.isSuccessful) AlbumArtMetadata() else AlbumArtMetadata(artworkUrl = artUrl)
        }
    }

    private suspend fun fetchMetadata(item: MediaItem, tmdbApiKey: String, log: MutableList<ScanLogEntry>): MediaMetadata {
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
        // (less common movies, most TV, most songs, home videos) will never match there. Fall
        // back to a real, official API for whatever we're missing before giving up on it: TMDb
        // for video (needs a free API key from Settings; simply skipped without one), MusicBrainz
        // + the Cover Art Archive for audio (no key needed).
        if (artworkUrl == null || year == null) {
            val query = title ?: guessedTitle
            if (item.kind == MediaKind.VIDEO) {
                if (tmdbApiKey.isNotBlank()) {
                    try {
                        val tmdb = fetchArtworkFromTmdb(tmdbApiKey, query, year)
                        log += ScanLogEntry("TMDb", if (tmdb.artworkUrl != null || tmdb.year != null) "found" else "not-found")
                        if (artworkUrl == null) artworkUrl = tmdb.artworkUrl
                        if (year == null) year = tmdb.year
                    } catch (e: Exception) {
                        log += ScanLogEntry("TMDb", "error", e.message)
                    }
                } else {
                    log += ScanLogEntry("TMDb", "not-found", "no API key configured in Settings")
                }
            } else {
                try {
                    val albumArt = fetchAlbumArtFromMusicBrainz(query)
                    log += ScanLogEntry("Album art", if (albumArt.artworkUrl != null) "found" else "not-found")
                    if (artworkUrl == null) artworkUrl = albumArt.artworkUrl
                } catch (e: Exception) {
                    log += ScanLogEntry("Album art", "error", e.message)
                }
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
        tmdbApiKey: String = "",
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
                            runCatching { item.id to resolve(item, networkShares, force, tmdbApiKey, onProgress) }
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
        tmdbApiKey: String,
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
        val fetched = fetchMetadata(item, tmdbApiKey, log)
        val metadata = if (!cached?.tags.isNullOrEmpty()) fetched.copy(tags = cached!!.tags) else fetched
        if (share != null && relPath != null) {
            runCatching { SmbSidecar.write(share, relPath, metadata) }
        }
        onProgress?.invoke(ScanProgressEvent(item.id, item.name, "done", log = log))
        return metadata
    }
}
