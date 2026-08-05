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
  openItem(item: MediaItem): Promise<void>;
  scanNetwork(): Promise<MediaItem[]>;
  runSearch(query: string, profileIds?: string[]): Promise<SearchResultItem[]>;
  downloadTorrent(url: string): Promise<void>;
}

declare global {
  interface Window {
    api: PreloadApi;
  }
}
