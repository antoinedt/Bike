// CommonJS on purpose (.cts -> preload.cjs): Electron preload scripts are the one process
// boundary where require() is the simplest, most compatible choice, independent of the rest
// of the app being ESM.
import { contextBridge, ipcRenderer } from "electron";

const api = {
  getSettings: () => ipcRenderer.invoke("settings:get"),
  updateTmdbApiKey: (tmdbApiKey: string) => ipcRenderer.invoke("settings:updateTmdbApiKey", tmdbApiKey),
  pickFolder: () => ipcRenderer.invoke("folder:pick"),
  addFolder: (path: string, label: string) => ipcRenderer.invoke("folder:add", path, label),
  updateFolder: (folder: unknown) => ipcRenderer.invoke("folder:update", folder),
  removeFolder: (id: string) => ipcRenderer.invoke("folder:remove", id),
  addShare: (share: unknown) => ipcRenderer.invoke("share:add", share),
  updateShare: (share: unknown) => ipcRenderer.invoke("share:update", share),
  removeShare: (id: string) => ipcRenderer.invoke("share:remove", id),
  testShare: (share: unknown) => ipcRenderer.invoke("share:test", share),
  addSiteProfile: (profile: unknown) => ipcRenderer.invoke("siteProfile:add", profile),
  updateSiteProfile: (profile: unknown) => ipcRenderer.invoke("siteProfile:update", profile),
  removeSiteProfile: (id: string) => ipcRenderer.invoke("siteProfile:remove", id),
  scanLibrary: () => ipcRenderer.invoke("library:scan"),
  getLibraryItems: () => ipcRenderer.invoke("library:getItems"),
  openItem: (item: unknown) => ipcRenderer.invoke("library:openItem", item),
  scanMetadata: (itemIds?: string[], force?: boolean) => ipcRenderer.invoke("metadata:scan", itemIds, force),
  onMetadataScanProgress: (callback: (event: unknown) => void) => {
    const listener = (_e: unknown, event: unknown) => callback(event);
    ipcRenderer.on("metadata:scanProgress", listener);
    return () => ipcRenderer.removeListener("metadata:scanProgress", listener);
  },
  cancelMetadataScan: () => ipcRenderer.send("metadata:scanCancel"),
  updateItemMetadata: (itemId: string, edits: unknown) => ipcRenderer.invoke("item:updateMetadata", itemId, edits),
  clearItemMetadata: (itemId: string) => ipcRenderer.invoke("item:clearMetadata", itemId),
  pickImageFile: () => ipcRenderer.invoke("item:pickImageFile"),
  scanNetwork: () => ipcRenderer.invoke("network:scan"),
  openNetworkStream: (item: unknown) => ipcRenderer.invoke("network:streamOpen", item),
  closeNetworkStream: (token: string) => ipcRenderer.invoke("network:streamClose", token),
  downloadNetworkItem: (item: unknown) => ipcRenderer.invoke("network:download", item),
  onNetworkDownloadProgress: (callback: (event: unknown) => void) => {
    const listener = (_e: unknown, event: unknown) => callback(event);
    ipcRenderer.on("network:downloadProgress", listener);
    return () => ipcRenderer.removeListener("network:downloadProgress", listener);
  },
  runSearch: (query: string, profileIds?: string[]) => ipcRenderer.invoke("search:run", query, profileIds),
  downloadTorrent: (url: string) => ipcRenderer.invoke("torrent:download", url),
  updateTorrentDownloadFolder: (folder: string) => ipcRenderer.invoke("settings:updateTorrentDownloadFolder", folder),
  getTorrentDownloadFolder: () => ipcRenderer.invoke("torrent:downloadFolder"),
  getTorrents: () => ipcRenderer.invoke("torrent:list"),
  addTorrent: (source: string) => ipcRenderer.invoke("torrent:add", source),
  addTorrentFile: () => ipcRenderer.invoke("torrent:addFile"),
  pauseTorrent: (id: string) => ipcRenderer.invoke("torrent:pause", id),
  resumeTorrent: (id: string) => ipcRenderer.invoke("torrent:resume", id),
  removeTorrent: (id: string, deleteFiles: boolean) => ipcRenderer.invoke("torrent:remove", id, deleteFiles),
  openTorrentFolder: (id: string) => ipcRenderer.invoke("torrent:openFolder", id),
  onTorrentUpdate: (callback: (torrents: unknown) => void) => {
    const listener = (_e: unknown, torrents: unknown) => callback(torrents);
    ipcRenderer.on("torrent:update", listener);
    return () => ipcRenderer.removeListener("torrent:update", listener);
  },
  onLibraryChanged: (callback: () => void) => {
    const listener = () => callback();
    ipcRenderer.on("library:changed", listener);
    return () => ipcRenderer.removeListener("library:changed", listener);
  },
  getLibraryScanStatus: () => ipcRenderer.invoke("library:getScanStatus"),
  getAppVersion: () => ipcRenderer.invoke("app:version"),
  openLog: () => ipcRenderer.invoke("app:openLog"),
  clearLog: () => ipcRenderer.invoke("app:clearLog"),
  getResume: (itemId: string) => ipcRenderer.invoke("resume:get", itemId),
  saveResume: (itemId: string, positionMs: number, durationMs: number, ended: boolean) =>
    ipcRenderer.invoke("resume:save", itemId, positionMs, durationMs, ended),
  getUpdateStatus: () => ipcRenderer.invoke("update:getStatus"),
  checkForUpdate: () => ipcRenderer.invoke("update:check"),
  installUpdateNow: () => ipcRenderer.invoke("update:installNow"),
  installUpdateLater: () => ipcRenderer.invoke("update:later"),
  onUpdateStatus: (callback: (status: unknown) => void) => {
    const listener = (_e: unknown, status: unknown) => callback(status);
    ipcRenderer.on("update:status", listener);
    return () => ipcRenderer.removeListener("update:status", listener);
  },
  onLibraryScanStatus: (callback: (status: unknown) => void) => {
    const listener = (_e: unknown, status: unknown) => callback(status);
    ipcRenderer.on("library:scanStatus", listener);
    return () => ipcRenderer.removeListener("library:scanStatus", listener);
  },
  copyItem: (item: unknown, target: unknown) => ipcRenderer.invoke("copy:start", item, target),
  getCopies: () => ipcRenderer.invoke("copy:list"),
  cancelCopy: (id: string) => ipcRenderer.invoke("copy:cancel", id),
  clearFinishedCopies: () => ipcRenderer.invoke("copy:clearFinished"),
  listCopyFolders: (target: unknown) => ipcRenderer.invoke("copy:listFolders", target),
  createCopyFolder: (target: unknown, name: string) => ipcRenderer.invoke("copy:createFolder", target, name),
  pickCopyFolder: () => ipcRenderer.invoke("copy:pickFolder"),
  updateRemoteSettings: (deviceName: string, remoteShareId: string, remoteLocalFolder?: string) =>
    ipcRenderer.invoke("remote:updateSettings", deviceName, remoteShareId, remoteLocalFolder ?? ""),
  getRemoteDevices: () => ipcRenderer.invoke("remote:devices"),
  sendToDevice: (deviceId: string, source: string, title?: string) => ipcRenderer.invoke("remote:send", deviceId, source, title),
  onRemoteEvent: (callback: (message: string) => void) => {
    const listener = (_e: unknown, message: string) => callback(message);
    ipcRenderer.on("remote:event", listener);
    return () => ipcRenderer.removeListener("remote:event", listener);
  },
  discoverCastTargets: () => ipcRenderer.invoke("cast:discover"),
  castItem: (targetId: string, item: unknown) => ipcRenderer.invoke("cast:start", targetId, item),
  castControl: (action: string, value?: number) => ipcRenderer.invoke("cast:control", action, value),
  stopCasting: () => ipcRenderer.invoke("cast:stop"),
  getCastState: () => ipcRenderer.invoke("cast:getState"),
  onCastState: (callback: (state: unknown) => void) => {
    const listener = (_e: unknown, state: unknown) => callback(state);
    ipcRenderer.on("cast:state", listener);
    return () => ipcRenderer.removeListener("cast:state", listener);
  },
  getTvStatus: () => ipcRenderer.invoke("tv:status"),
  tvSignIn: () => ipcRenderer.invoke("tv:signIn"),
  tvSignOut: () => ipcRenderer.invoke("tv:signOut"),
  getTvChannels: (forceRefresh?: boolean) => ipcRenderer.invoke("tv:channels", forceRefresh),
  getTvGuide: (channelIds: number[], from: number, to: number) => ipcRenderer.invoke("tv:guide", channelIds, from, to),
  getTvStream: (channelId: number) => ipcRenderer.invoke("tv:stream", channelId),
  castChannel: (targetId: string, channelId: number, channelName: string) => ipcRenderer.invoke("tv:castLive", targetId, channelId, channelName),
  getWidevineStatus: () => ipcRenderer.invoke("tv:widevineStatus"),
  onCopyUpdate: (callback: (jobs: unknown) => void) => {
    const listener = (_e: unknown, jobs: unknown) => callback(jobs);
    ipcRenderer.on("copy:update", listener);
    return () => ipcRenderer.removeListener("copy:update", listener);
  }
};

contextBridge.exposeInMainWorld("api", api);
