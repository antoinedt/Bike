import type {
  AppSettings,
  DownloadProgressEvent,
  LocalFolder,
  MediaItem,
  NetworkShare,
  ScanProgressEvent,
  SearchResultItem,
  SiteProfile
} from "./types.js";

export interface PreloadApi {
  getSettings(): Promise<AppSettings>;
  /** Saves the free API key from themoviedb.org/settings/api, used by the TMDb artwork/year fallback for video items. An empty string disables that fallback (it's simply skipped). */
  updateTmdbApiKey(tmdbApiKey: string): Promise<void>;
  pickFolder(): Promise<string | null>;
  addFolder(path: string, label: string): Promise<LocalFolder>;
  updateFolder(folder: LocalFolder): Promise<void>;
  removeFolder(id: string): Promise<void>;
  addShare(share: Omit<NetworkShare, "id">): Promise<NetworkShare>;
  updateShare(share: NetworkShare): Promise<void>;
  removeShare(id: string): Promise<void>;
  testShare(share: NetworkShare): Promise<{ ok: boolean; error?: string }>;
  addSiteProfile(profile: Omit<SiteProfile, "id">): Promise<SiteProfile>;
  updateSiteProfile(profile: SiteProfile): Promise<void>;
  removeSiteProfile(id: string): Promise<void>;
  scanLibrary(): Promise<MediaItem[]>;
  getLibraryItems(): Promise<MediaItem[]>;
  /** Opens a local-device item with the OS's default app. Not valid for network items. */
  openItem(item: MediaItem): Promise<void>;
  /**
   * Fills in title/year/length/cover art for every video/audio item not yet checked (win or lose
   * — see MediaItem.infoFetched), local and network, and writes a metadata sidecar file next to
   * each one (uploaded to the share for network items) so it's there for next time — on this
   * device or any other that browses the same share. Checks for an existing sidecar before
   * fetching anything, so a re-run only costs time for items that are genuinely new. Pass
   * `itemIds` to scope the fetch to a specific set (e.g. the folder currently being browsed,
   * optionally expanded to include subfolders); omit it to scan the whole library. Pass
   * `force: true` to re-fetch every item in scope regardless of whether it was already checked,
   * overriding whatever artwork/year was found before — for retrying a stuck "no match" result.
   * Can take a while the first time on a large scope — each un-cached item needs a real (hidden)
   * duration probe, not just an HTTP lookup.
   */
  scanMetadata(itemIds?: string[], force?: boolean): Promise<MediaItem[]>;
  /**
   * Subscribes to live progress events during a scanMetadata run — one "started" event per item
   * as it begins, followed by "done" (with a step-by-step log: iTunes/TMDb (video, needs an API
   * key)/Album art (audio, via MusicBrainz)/Video frame/Duration, each found/not-found/error —
   * this is the "why" for an item that came up with nothing), "cached" (a previous result was
   * reused, no new lookup needed), or "error" (an
   * unexpected failure outside the normal steps). Returns an unsubscribe function; call it when
   * the progress dialog closes so events from a later scan don't leak into a stale listener.
   */
  onMetadataScanProgress(callback: (event: ScanProgressEvent) => void): () => void;
  /** Stops the in-flight scanMetadata run from picking up any further items — whatever was already found for earlier items is kept. */
  cancelMetadataScan(): void;
  /**
   * Manually overwrites title/year/tags/artworkUrl for one item — unlike scanMetadata, these
   * values take precedence unconditionally, so this is also how you correct a wrong auto-fetched
   * title or add artwork after a failed fetch. Only the fields present in `edits` change; omit a
   * field to leave it as-is. Persists the same way as an auto fetch: this device's database, and
   * the item's sidecar (uploaded to the share, for a network item). Always marks the item as
   * fetched, so it won't be silently re-attempted by a later scanMetadata call.
   */
  updateItemMetadata(
    itemId: string,
    edits: { title?: string; year?: string; tags?: string[]; artworkUrl?: string }
  ): Promise<MediaItem[]>;
  /**
   * Wipes title/year/tags/artworkUrl for one item and resets it to "not yet checked" — lets the
   * user start over on an item that got the wrong auto-fetched info, without having to force a
   * whole rescan. Persists the same way as every other metadata change: this device's database,
   * and the item's sidecar (cleared on the share too, for a network item).
   */
  clearItemMetadata(itemId: string): Promise<MediaItem[]>;
  /** Opens a native file-picker for an image and returns it as a data: URL, or null if cancelled. */
  pickImageFile(): Promise<string | null>;
  scanNetwork(): Promise<MediaItem[]>;
  /** Starts a live byte-range relay for a network item and returns a URL an in-app <video>/<audio> element can play directly. */
  openNetworkStream(item: MediaItem): Promise<{ url: string; token: string }>;
  closeNetworkStream(token: string): Promise<void>;
  /** Prompts for a save location and saves a full local copy of a network item. Null if the user cancels. */
  downloadNetworkItem(item: MediaItem): Promise<string | null>;
  /**
   * Subscribes to live byte-progress events for the in-flight downloadNetworkItem call. Returns
   * an unsubscribe function; call it once the download settles so events from a later download
   * don't leak into a stale listener.
   */
  onNetworkDownloadProgress(callback: (event: DownloadProgressEvent) => void): () => void;
  runSearch(query: string, profileIds?: string[]): Promise<SearchResultItem[]>;
  downloadTorrent(url: string): Promise<void>;
}

declare global {
  interface Window {
    api: PreloadApi;
  }
}
