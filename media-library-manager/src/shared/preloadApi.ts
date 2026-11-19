import type {
  AppSettings,
  LocalFolder,
  MediaItem,
  NetworkShare,
  SearchResultItem,
  SiteProfile
} from "./types.js";

export interface PreloadApi {
  getSettings(): Promise<AppSettings>;
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
   * Fills in title/year/length/cover art for every video/audio item missing them, local and
   * network, and writes a metadata sidecar file next to each one (uploaded to the share for
   * network items) so it's there for next time — on this device or any other that browses the
   * same share. Checks for an existing sidecar before fetching anything, so a re-run only costs
   * time for items that are genuinely new. Can take a while on a large library the first time —
   * each un-cached item needs a real (hidden) duration probe, not just an HTTP lookup.
   */
  scanMetadata(): Promise<MediaItem[]>;
  /**
   * Manually overwrites title/year/tags for one item — unlike scanMetadata, these values take
   * precedence unconditionally, so this is also how you correct a wrong auto-fetched title. Only
   * the fields present in `edits` change; omit a field to leave it as-is. Persists the same way
   * as an auto fetch: this device's database, and the item's sidecar (uploaded to the share, for
   * a network item).
   */
  updateItemMetadata(itemId: string, edits: { title?: string; year?: string; tags?: string[] }): Promise<MediaItem[]>;
  scanNetwork(): Promise<MediaItem[]>;
  /** Starts a live byte-range relay for a network item and returns a URL an in-app <video>/<audio> element can play directly. */
  openNetworkStream(item: MediaItem): Promise<{ url: string; token: string }>;
  closeNetworkStream(token: string): Promise<void>;
  /** Prompts for a save location and saves a full local copy of a network item. Null if the user cancels. */
  downloadNetworkItem(item: MediaItem): Promise<string | null>;
  runSearch(query: string, profileIds?: string[]): Promise<SearchResultItem[]>;
  downloadTorrent(url: string): Promise<void>;
}

declare global {
  interface Window {
    api: PreloadApi;
  }
}
