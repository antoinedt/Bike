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
  downloadTorrent: (url: string) => ipcRenderer.invoke("torrent:download", url)
};

contextBridge.exposeInMainWorld("api", api);
