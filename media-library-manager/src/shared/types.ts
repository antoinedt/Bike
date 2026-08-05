export type MediaKind = "video" | "audio" | "image" | "other";

export type MediaSource =
  | { type: "local"; folderId: string }
  | { type: "network"; shareId: string };

export interface MediaItem {
  id: string;
  name: string;
  /** Absolute local path, or "smb://host/share/path" for network items not mirrored locally. */
  path: string;
  kind: MediaKind;
  extension: string;
  sizeBytes: number;
  modifiedAt: number;
  source: MediaSource;
  /** Populated on demand by the artwork fetcher; absent until fetched (or if nothing suitable was found). */
  artworkUrl?: string;
}

export interface LocalFolder {
  id: string;
  path: string;
  label: string;
}

export interface NetworkShare {
  id: string;
  label: string;
  host: string;
  share: string;
  /** Sub-path within the share to scan, "" for the share root. */
  subPath: string;
  domain: string;
  username: string;
  /** Stored as given; the OS keychain is out of scope for this build, see README security note. */
  password: string;
}

/**
 * Describes how to search a single user-provided site and parse its results page.
 * Selectors are plain CSS selectors evaluated with cheerio against the fetched HTML.
 */
export interface SiteProfile {
  id: string;
  label: string;
  /** Use {query} as the placeholder for the URL-encoded search term. */
  searchUrlTemplate: string;
  resultItemSelector: string;
  titleSelector: string;
  /** Selector (relative to the result item) for the <a> whose href is the magnet link or .torrent URL, or a detail-page link. */
  linkSelector: string;
  sizeSelector: string;
  seedersSelector: string;
  /**
   * If the link found via linkSelector points to a detail page rather than directly to a
   * magnet:/.torrent URL, set this to the selector (evaluated on the detail page) that finds
   * the actual download link.
   */
  detailPageLinkSelector: string;
}

export interface SearchResultItem {
  title: string;
  downloadUrl: string;
  size: string;
  seeders: string;
  sourceProfileId: string;
  sourceProfileLabel: string;
}

export interface AppSettings {
  localFolders: LocalFolder[];
  networkShares: NetworkShare[];
  siteProfiles: SiteProfile[];
}

export const MEDIA_EXTENSIONS: Record<Exclude<MediaKind, "other">, string[]> = {
  video: ["mp4", "mkv", "avi", "mov", "webm", "m4v", "wmv", "flv", "mpg", "mpeg"],
  audio: ["mp3", "flac", "wav", "m4a", "ogg", "aac", "wma", "opus"],
  image: ["jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff"]
};

export function classifyExtension(ext: string): MediaKind {
  const lower = ext.toLowerCase().replace(/^\./, "");
  for (const [kind, exts] of Object.entries(MEDIA_EXTENSIONS)) {
    if (exts.includes(lower)) return kind as MediaKind;
  }
  return "other";
}
