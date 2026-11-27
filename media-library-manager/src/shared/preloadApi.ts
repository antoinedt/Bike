import type {
  AppSettings,
  CastState,
  CastTarget,
  CopyJob,
  CopyTarget,
  DownloadProgressEvent,
  LibraryScanStatus,
  UpdateStatus,
  LocalFolder,
  MediaItem,
  NetworkShare,
  RemoteDeviceInfo,
  ScanProgressEvent,
  SearchResultItem,
  SiteProfile,
  TorrentStatus,
  TvChannel,
  TvProgram,
  TvResult,
  TvStatus,
  TvStreamInfo
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
  /** Hands a magnet/.torrent link to the OS's default torrent app instead of downloading in-app. */
  downloadTorrent(url: string): Promise<void>;
  /** Empty resets to the default folder. */
  updateTorrentDownloadFolder(folder: string): Promise<void>;
  /** Where in-app downloads go right now (the setting, or the default). */
  getTorrentDownloadFolder(): Promise<string>;
  getTorrents(): Promise<TorrentStatus[]>;
  /** Starts an in-app download from a magnet link, info hash, or http(s) .torrent URL. */
  addTorrent(source: string): Promise<TorrentStatus>;
  /** Opens a file picker for a .torrent file and starts downloading it; null if cancelled. */
  addTorrentFile(): Promise<TorrentStatus | null>;
  pauseTorrent(id: string): Promise<TorrentStatus[]>;
  resumeTorrent(id: string): Promise<TorrentStatus[]>;
  /** With deleteFiles, also deletes what it downloaded. */
  removeTorrent(id: string, deleteFiles: boolean): Promise<TorrentStatus[]>;
  /** Shows the download in the OS file manager. */
  openTorrentFolder(id: string): Promise<void>;
  /** Live list of downloads, pushed about once a second while any is active. Returns an unsubscribe function. */
  onTorrentUpdate(callback: (torrents: TorrentStatus[]) => void): () => void;
  /** Fired when a finished download was added to the Library. Returns an unsubscribe function. */
  onLibraryChanged(callback: () => void): () => void;
  /** Whether the background library scan is running, and how much it's found. */
  getLibraryScanStatus(): Promise<LibraryScanStatus>;
  /** The app's version, e.g. "0.2.36" (the last number is the build). */
  getAppVersion(): Promise<string>;
  /** Opens OmniPlay's log file (search, updates…); resolves with its path. */
  openLog(): Promise<string>;
  getUpdateStatus(): Promise<UpdateStatus>;
  /** Checks GitHub for a newer build and downloads it; resolves with the resulting status. */
  checkForUpdate(): Promise<UpdateStatus>;
  /** Quits, installs the downloaded update and starts the new version. */
  installUpdateNow(): Promise<void>;
  /** Install the downloaded update when the app is next closed instead. */
  installUpdateLater(): Promise<void>;
  onUpdateStatus(callback: (status: UpdateStatus) => void): () => void;
  /** Live scan status. Returns an unsubscribe function. */
  onLibraryScanStatus(callback: (status: LibraryScanStatus) => void): () => void;
  /** "Copy to…": queues a copy of a library item (local or network) into a folder on this computer or a share. */
  copyItem(item: MediaItem, target: CopyTarget): Promise<CopyJob>;
  getCopies(): Promise<CopyJob[]>;
  cancelCopy(id: string): Promise<void>;
  clearFinishedCopies(): Promise<CopyJob[]>;
  /** Folder names directly inside a copy target, for the folder picker. */
  listCopyFolders(target: CopyTarget): Promise<string[]>;
  createCopyFolder(target: CopyTarget, name: string): Promise<void>;
  /** OS folder picker; null if cancelled. */
  pickCopyFolder(): Promise<string | null>;
  /** Live list of copies. Returns an unsubscribe function. */
  onCopyUpdate(callback: (jobs: CopyJob[]) => void): () => void;
  /** "Send to another device": this computer's name and the mailbox share ("" = off). */
  updateRemoteSettings(deviceName: string, remoteShareId: string, remoteLocalFolder?: string): Promise<void>;
  /** Other devices using the same mailbox share. */
  getRemoteDevices(): Promise<RemoteDeviceInfo[]>;
  /** Starts a download on another device (fetches a .torrent link here first). Returns the name sent. */
  sendToDevice(deviceId: string, source: string, title?: string): Promise<string>;
  /** "Started X on TV" / "Started X sent from Phone" messages. Returns an unsubscribe function. */
  onRemoteEvent(callback: (message: string) => void): () => void;
  /** Google TV / Chromecast and DLNA TVs (e.g. Samsung) on the network; takes ~3 s. */
  discoverCastTargets(): Promise<CastTarget[]>;
  /** Plays a library item on a TV; the TV streams it from this app. */
  castItem(targetId: string, item: MediaItem): Promise<void>;
  castControl(action: "play" | "pause" | "seek", value?: number): Promise<void>;
  stopCasting(): Promise<void>;
  getCastState(): Promise<CastState | null>;
  /** Live casting state (null when nothing is cast). Returns an unsubscribe function. */
  onCastState(callback: (state: CastState | null) => void): () => void;
  /** TV tab: whether a Fizz sign-in is saved. */
  getTvStatus(): Promise<TvStatus>;
  /** Opens Fizz's sign-in page in a window; resolves once signed in (or with the error / cancel). */
  tvSignIn(): Promise<TvResult<TvStatus>>;
  tvSignOut(): Promise<void>;
  /** The channels in your Fizz plan. */
  getTvChannels(forceRefresh?: boolean): Promise<TvResult<TvChannel[]>>;
  /** Guide listings for these channels between from and to (epoch ms). */
  getTvGuide(channelIds: number[], from: number, to: number): Promise<TvResult<TvProgram[]>>;
  /** A playable live stream for a channel (with its Widevine token + key id). */
  getTvStream(channelId: number): Promise<TvResult<TvStreamInfo>>;
  /** Casts a live channel to a Google TV (reuses the cast target picked with discoverCastTargets). */
  castChannel(targetId: string, channelId: number, channelName: string): Promise<TvResult<boolean>>;
  /** How the Castlabs Widevine component init went at startup (for diagnosing DRM failures). */
  getWidevineStatus(): Promise<string>;
}

declare global {
  interface Window {
    api: PreloadApi;
  }
}
