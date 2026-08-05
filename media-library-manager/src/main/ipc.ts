import { dialog, ipcMain, shell } from "electron";
import { IPC } from "../shared/ipc.js";
import type { MediaItem, NetworkShare, SiteProfile } from "../shared/types.js";
import {
  addLocalFolder,
  addNetworkShare,
  addSiteProfile,
  getSettings,
  removeLocalFolder,
  removeNetworkShare,
  removeSiteProfile,
  updateSiteProfile
} from "./settings.js";
import { scanLocalFolders } from "./library/scanner.js";
import { relPathFromMediaPath, scanNetworkShare, testShareConnection } from "./network/smbClient.js";
import { ensureStreamServer, registerStream, unregisterStream } from "./network/streamServer.js";
import { searchSites } from "./search/siteSearch.js";
import { handOffDownload } from "./torrent/handoff.js";
import { getAllItems, replaceItemsForSource } from "./db.js";

export function registerIpcHandlers(): void {
  ipcMain.handle(IPC.settingsGet, () => getSettings());

  ipcMain.handle(IPC.folderPick, async () => {
    const result = await dialog.showOpenDialog({ properties: ["openDirectory"] });
    if (result.canceled || result.filePaths.length === 0) return null;
    return result.filePaths[0];
  });

  ipcMain.handle(IPC.folderAdd, (_e, folderPath: string, label: string) => addLocalFolder(folderPath, label));
  ipcMain.handle(IPC.folderRemove, (_e, id: string) => removeLocalFolder(id));

  ipcMain.handle(IPC.shareAdd, (_e, share: Omit<NetworkShare, "id">) => addNetworkShare(share));
  ipcMain.handle(IPC.shareRemove, (_e, id: string) => removeNetworkShare(id));
  ipcMain.handle(IPC.shareTest, (_e, share: NetworkShare) => testShareConnection(share));

  ipcMain.handle(IPC.siteProfileAdd, (_e, profile: Omit<SiteProfile, "id">) => addSiteProfile(profile));
  ipcMain.handle(IPC.siteProfileUpdate, (_e, profile: SiteProfile) => updateSiteProfile(profile));
  ipcMain.handle(IPC.siteProfileRemove, (_e, id: string) => removeSiteProfile(id));

  ipcMain.handle(IPC.libraryScan, async () => {
    const { localFolders } = getSettings();
    const items = await scanLocalFolders(localFolders);
    await replaceItemsForSource((item) => item.source.type === "local", items);
    return getAllItems();
  });

  ipcMain.handle(IPC.libraryGetItems, () => getAllItems());

  ipcMain.handle(IPC.libraryOpenItem, async (_e, item: MediaItem) => {
    if (item.source.type !== "local") {
      throw new Error("Network items are streamed, not opened directly — use openNetworkStream.");
    }
    const openError = await shell.openPath(item.path);
    if (openError) throw new Error(openError);
  });

  ipcMain.handle(IPC.networkScan, async () => {
    const { networkShares } = getSettings();
    const results = await Promise.all(networkShares.map(scanNetworkShare));
    const items = results.flat();
    await replaceItemsForSource((item) => item.source.type === "network", items);
    return getAllItems();
  });

  ipcMain.handle(IPC.networkStreamOpen, async (_e, item: MediaItem) => {
    const source = item.source;
    if (source.type !== "network") throw new Error("Not a network item.");
    const { networkShares } = getSettings();
    const share = networkShares.find((s) => s.id === source.shareId);
    if (!share) throw new Error("Network share for this item is no longer configured.");
    const relPath = relPathFromMediaPath(share, item.path);
    const port = await ensureStreamServer();
    const token = registerStream(share, relPath, item.extension);
    return { url: `http://127.0.0.1:${port}/stream/${token}`, token };
  });

  ipcMain.handle(IPC.networkStreamClose, (_e, token: string) => {
    unregisterStream(token);
  });

  ipcMain.handle(IPC.searchRun, async (_e, query: string, profileIds?: string[]) => {
    const { siteProfiles } = getSettings();
    const profiles = profileIds?.length
      ? siteProfiles.filter((p) => profileIds.includes(p.id))
      : siteProfiles;
    return searchSites(profiles, query);
  });

  ipcMain.handle(IPC.torrentDownload, (_e, url: string) => handOffDownload(url));
}
