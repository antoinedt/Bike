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
  /** The fields below are populated on demand by the metadata scanner (Settings -> Scan metadata). */
  title?: string;
  year?: string;
  durationSeconds?: number;
  /** User-assigned, free-form; never auto-fetched. Empty/absent means untagged. */
  tags?: string[];
  /**
   * True once a "Fetch artwork & info" attempt has completed for this item, win or lose. Distinct
   * from having a title: guessTitle() always produces *something* from the filename, so "has a
   * title" can't tell a real match from "we looked and found nothing" — without this flag, an
   * item that genuinely has no match would look identical to one never checked, and a failed item
   * would get silently retried forever (or, before this existed, a guessed title made it look
   * "done" and get skipped forever instead). This is what lets the UI show "no info found — add
   * it yourself" instead of just doing nothing.
   */
  infoFetched?: boolean;
}

/** The subset of MediaItem's on-demand fields that the metadata scanner produces and persists as a sidecar. */
export interface MediaMetadata {
  title?: string;
  year?: string;
  durationSeconds?: number;
  artworkUrl?: string;
  tags?: string[];
  infoFetched?: boolean;
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
  /**
   * Set this when the site renders its results with JavaScript (check "View Page Source" vs.
   * "Inspect" — if your selectors only match in Inspect, this is why). Fetches for this profile
   * go through a hidden Electron BrowserWindow instead of a plain HTTP request, so the page
   * actually executes its JS before scraping. Slower; leave off for ordinary server-rendered
   * sites.
   */
  useHeadlessBrowser: boolean;
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
  /** Free key from themoviedb.org/settings/api — used for the TMDb artwork/year fallback (movies & TV) when iTunes has nothing. Empty means that fallback is skipped. */
  tmdbApiKey: string;
  /** Where in-app torrent downloads are saved. Empty means the default (Downloads/Media Library Manager). */
  torrentDownloadFolder: string;
  /** This computer's id for "Send to another device" (made up once, then kept). */
  deviceId: string;
  /** How this computer shows up on other devices ("" = the computer's name). */
  deviceName: string;
  /** The network share used as the mailbox for "Send to another device"; "" = off. */
  remoteShareId: string;
  /** Or a folder on this PC (Windows) used as that mailbox — e.g. one it shares with the phone; "" = not used. */
  remoteLocalFolder: string;
}

export interface RemoteDeviceInfo {
  id: string;
  name: string;
  platform: string;
  lastSeen: number;
  online: boolean;
}

/** One in-app torrent download, as shown on the Downloads page. */
export interface TorrentStatus {
  /** The torrent's info hash once known; a temporary id before that (e.g. while a .torrent URL downloads). */
  id: string;
  name: string;
  /** "metadata": connected to the swarm but still fetching the file list (magnet links). */
  state: "metadata" | "downloading" | "paused" | "done" | "error";
  /** 0..1 */
  progress: number;
  /** Bytes per second. */
  downloadSpeed: number;
  uploadSpeed: number;
  peers: number;
  /** Total size in bytes; 0 until the file list is known. */
  length: number;
  downloaded: number;
  /** Estimated time left, or null when unknown (no peers, or not downloading). */
  timeRemainingMs: number | null;
  /** Folder the torrent's files are saved into. */
  savePath: string;
  error?: string;
  addedAt: number;
}

/** One lookup step's outcome for a single item during "Fetch artwork & info" — what the progress dialog's log is built from. */
export interface ScanLogEntry {
  step: "iTunes" | "TMDb" | "Album art" | "Video frame" | "Duration";
  result: "found" | "not-found" | "error";
  detail?: string;
}

/** A live progress event pushed from main to renderer while a network item downloads. */
export interface DownloadProgressEvent {
  bytesWritten: number;
  totalBytes: number;
}

/** A live progress event pushed from main to renderer while "Fetch artwork & info" runs, one per item per phase. */
export interface ScanProgressEvent {
  itemId: string;
  itemName: string;
  status: "started" | "done" | "cached" | "error";
  log?: ScanLogEntry[];
  /** Set when status is "error" — an unexpected failure outside the normal found/not-found steps (e.g. couldn't write the sidecar). */
  error?: string;
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

/**
 * A folder "Copy to…" can put files in: inside a configured local folder, inside a configured
 * network share (relative to its sub-path), or any folder on this computer picked with the OS
 * dialog.
 */
export type CopyTarget =
  | { kind: "folder"; folderId: string; relPath: string }
  | { kind: "share"; shareId: string; relPath: string }
  | { kind: "path"; path: string };

export interface CopyJob {
  id: string;
  itemId: string;
  itemName: string;
  target: CopyTarget;
  targetLabel: string;
  state: "queued" | "running" | "done" | "failed";
  copiedBytes: number;
  totalBytes: number;
  /** The name it was saved under, when that differs (the original was taken). */
  savedAs?: string;
  error?: string;
}

/** A TV the app can cast to. */
export interface CastTarget {
  id: string;
  name: string;
  /** Google TV / Chromecast (Google Cast) or a DLNA renderer such as a Samsung TV. */
  kind: "chromecast" | "dlna";
  host: string;
  port?: number;
  controlUrl?: string;
}

/** Self-update from GitHub releases (Windows app). */
export interface UpdateStatus {
  state: "idle" | "checking" | "up-to-date" | "downloading" | "ready" | "installing" | "error";
  /** This app's version, e.g. "0.2.36". */
  current: string;
  /** The newest published version, once known. */
  latest?: string;
  /** 0..1 while downloading. */
  progress?: number;
  error?: string;
}

/** The background library scan, for the Library page's "Scanning…" indicator. */
export interface LibraryScanStatus {
  scanning: boolean;
  /** Media files found so far by the current (or last) scan. */
  found: number;
}

export interface CastState {
  deviceName: string;
  deviceKind: "chromecast" | "dlna";
  itemName: string;
  state: "loading" | "playing" | "paused" | "buffering" | "stopped" | "error";
  /** Seconds. */
  position: number;
  duration: number;
  error?: string;
}

/** TV tab (Fizz TV "My plan" guide). */
export interface TvChannel {
  id: number;
  number: number | null;
  name: string;
  logoUrl: string | null;
}

export interface TvProgram {
  key: string;
  channelId: number;
  title: string;
  episode: string | null;
  description: string | null;
  /** Epoch milliseconds. */
  start: number;
  end: number;
  imageUrl: string | null;
}

export interface TvStatus {
  signedIn: boolean;
  email: string | null;
}

/** TV calls answer with this instead of throwing, so the page can tell "sign in again" from other errors. */
export type TvResult<T> = { ok: true; value: T } | { ok: false; signInRequired: boolean; error: string };

/** A playable live stream for a channel, for the renderer's TV player. */
export interface TvStreamInfo {
  url: string;
  isDash: boolean;
  /** Widevine licence token, or null for a clear channel. */
  drmToken: string | null;
  licenseUrl: string;
  /** The content key id (from the DASH manifest), needed by the Vualto licence envelope. */
  kid: string | null;
}
