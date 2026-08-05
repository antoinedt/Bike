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
  removeFolder(id: string): Promise<void>;
  addShare(share: Omit<NetworkShare, "id">): Promise<NetworkShare>;
  removeShare(id: string): Promise<void>;
  testShare(share: NetworkShare): Promise<{ ok: boolean; error?: string }>;
  addSiteProfile(profile: Omit<SiteProfile, "id">): Promise<SiteProfile>;
  updateSiteProfile(profile: SiteProfile): Promise<void>;
  removeSiteProfile(id: string): Promise<void>;
  scanLibrary(): Promise<MediaItem[]>;
  getLibraryItems(): Promise<MediaItem[]>;
  /** Opens a local-device item with the OS's default app. Not valid for network items. */
  openItem(item: MediaItem): Promise<void>;
  scanNetwork(): Promise<MediaItem[]>;
  /** Starts a live byte-range relay for a network item and returns a URL an in-app <video>/<audio> element can play directly. */
  openNetworkStream(item: MediaItem): Promise<{ url: string; token: string }>;
  closeNetworkStream(token: string): Promise<void>;
  runSearch(query: string, profileIds?: string[]): Promise<SearchResultItem[]>;
  downloadTorrent(url: string): Promise<void>;
}

declare global {
  interface Window {
    api: PreloadApi;
  }
}
