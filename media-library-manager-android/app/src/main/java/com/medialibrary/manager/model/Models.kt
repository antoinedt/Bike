package com.medialibrary.manager.model

import kotlinx.serialization.Serializable
import java.util.UUID

enum class MediaKind { VIDEO, AUDIO, IMAGE, OTHER }

enum class MediaSourceType { LOCAL, NETWORK }

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "wmv", "flv", "mpg", "mpeg")
private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "ogg", "aac", "wma", "opus")
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff")

fun classifyExtension(extension: String): MediaKind {
    val lower = extension.lowercase().removePrefix(".")
    return when {
        lower in VIDEO_EXTENSIONS -> MediaKind.VIDEO
        lower in AUDIO_EXTENSIONS -> MediaKind.AUDIO
        lower in IMAGE_EXTENSIONS -> MediaKind.IMAGE
        else -> MediaKind.OTHER
    }
}

/**
 * A scanned media file.
 *
 * [path] means different things per [sourceType]: for LOCAL it's a `content://` MediaStore URI
 * (openable directly by any app via an ACTION_VIEW intent); for NETWORK it's `smb://<shareId>/<relPath>`
 * where `<shareId>` is looked up against [NetworkShare.id] at play time to resolve real
 * host/credentials — the id, not the host, is embedded so nothing sensitive ends up in a URI
 * that might be logged.
 */
@Serializable
data class MediaItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String,
    val kind: MediaKind,
    val extension: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val sourceType: MediaSourceType,
    /** Only meaningful when [sourceType] is NETWORK. */
    val shareId: String? = null,
    /** Only meaningful when [sourceType] is LOCAL and it came from a configured [LocalFolder] (not a whole-device MediaStore scan). Lets the browser tell two configured folders' items apart when their relative paths would otherwise collide. */
    val folderId: String? = null,
    /** Populated on demand by ArtworkFetcher; null until fetched (or if nothing suitable was found). */
    val artworkUrl: String? = null,
    /**
     * The folder this item sits in, relative to its configured local folder / network share root
     * ("" for the root itself, "Movies/Action" for a nested one) — captured at scan time by each
     * scanner, since it's cheap to track while walking and far more reliable than trying to parse
     * it back out of a content:// URI or MediaStore id after the fact.
     */
    val folderPath: String = "",
    /** The fields below are populated on demand by "Fetch artwork & info"; null until fetched. */
    val title: String? = null,
    val year: String? = null,
    /** User-assigned, free-form, via the per-item edit dialog; never auto-fetched. */
    val tags: List<String> = emptyList(),
    /**
     * True once a "Fetch artwork & info" attempt has completed for this item, win or lose. Distinct
     * from having a title: guessTitle() always produces *something* from the filename, so "has a
     * title" can't tell a real match from "we looked and found nothing" — without this flag, an
     * item that genuinely has no match would look identical to one never checked, and a failed item
     * would get silently retried forever (or, before this existed, a guessed title made it look
     * "done" and get skipped forever instead). This is what lets the UI show "no info found — add
     * it yourself" instead of just doing nothing.
     */
    val infoFetched: Boolean = false
)

/** The subset of MediaItem's on-demand fields that "Fetch artwork & info" produces, and that an SMB sidecar persists. */
@Serializable
data class MediaMetadata(
    val title: String? = null,
    val year: String? = null,
    val artworkUrl: String? = null,
    val tags: List<String> = emptyList(),
    val infoFetched: Boolean = false
)

/** A user-picked device folder (SAF tree URI) to scope the local scan to. */
@Serializable
data class LocalFolder(
    val id: String = UUID.randomUUID().toString(),
    val treeUri: String,
    val label: String
)

@Serializable
data class NetworkShare(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val host: String,
    val share: String,
    val subPath: String = "",
    val domain: String = "WORKGROUP",
    val username: String = "",
    val password: String = ""
)

/**
 * Describes how to search a single user-provided site and parse its results page.
 * Selectors are plain CSS selectors evaluated with Jsoup against the fetched HTML.
 */
@Serializable
data class SiteProfile(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    /** Use {query} as the placeholder for the URL-encoded search term. */
    val searchUrlTemplate: String,
    val resultItemSelector: String,
    val titleSelector: String,
    /** Selector (relative to the result item) for the <a> whose href is the magnet/.torrent link, or a detail-page link. */
    val linkSelector: String,
    val sizeSelector: String = "",
    val seedersSelector: String = "",
    /** If linkSelector points to a detail page rather than the download link directly, the selector to find it there. */
    val detailPageLinkSelector: String = "",
    /**
     * Set this when the site renders its results with JavaScript (check "View Page Source" vs.
     * the Layout Inspector — if your selectors only match there, this is why). Fetches for this
     * profile go through an off-screen WebView instead of a plain HTTP request, so the page
     * actually executes its JS before scraping. Slower; leave off for ordinary server-rendered
     * sites.
     */
    val useHeadlessBrowser: Boolean = false
)

data class SearchResultItem(
    val title: String,
    val downloadUrl: String,
    val size: String,
    val seeders: String,
    val sourceProfileId: String,
    val sourceProfileLabel: String
)

@Serializable
data class AppSettings(
    val localFolders: List<LocalFolder> = emptyList(),
    val networkShares: List<NetworkShare> = emptyList(),
    val siteProfiles: List<SiteProfile> = emptyList()
)
