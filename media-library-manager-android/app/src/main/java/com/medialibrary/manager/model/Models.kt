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
    val shareId: String? = null
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
    val detailPageLinkSelector: String = ""
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
    val networkShares: List<NetworkShare> = emptyList(),
    val siteProfiles: List<SiteProfile> = emptyList()
)
