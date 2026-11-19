import { dialog, ipcMain, shell } from "electron";
import { readFile } from "node:fs/promises";
import { IPC } from "../shared/ipc.js";
import type { LocalFolder, MediaItem, NetworkShare, SiteProfile } from "../shared/types.js";
import {
  addLocalFolder,
  addNetworkShare,
  addSiteProfile,
  getSettings,
  removeLocalFolder,
  removeNetworkShare,
  removeSiteProfile,
  updateLocalFolder,
  updateNetworkShare,
  updateSiteProfile
} from "./settings.js";
import { scanLocalFolders } from "./library/scanner.js";
import { relPathFromMediaPath, scanNetworkShare, testShareConnection } from "./network/smbClient.js";
import { downloadRawFile } from "./network/smbRawClient.js";
import { ensureStreamServer, registerStream, unregisterStream } from "./network/streamServer.js";
import { searchSites } from "./search/siteSearch.js";
import { handOffDownload } from "./torrent/handoff.js";
import { applyManualEdit, applyMetadata, getAllItems, replaceItemsForSource } from "./db.js";
import { readSidecar, scanMetadataForLibrary, writeSidecar } from "./metadata/metadataScanner.js";

export function registerIpcHandlers(): void {
  ipcMain.handle(IPC.settingsGet, () => getSettings());

  ipcMain.handle(IPC.folderPick, async () => {
    const result = await dialog.showOpenDialog({ properties: ["openDirectory"] });
    if (result.canceled || result.filePaths.length === 0) return null;
    return result.filePaths[0];
  });

  ipcMain.handle(IPC.folderAdd, (_e, folderPath: string, label: string) => addLocalFolder(folderPath, label));
  ipcMain.handle(IPC.folderUpdate, (_e, folder: LocalFolder) => updateLocalFolder(folder));
  ipcMain.handle(IPC.folderRemove, (_e, id: string) => removeLocalFolder(id));

  ipcMain.handle(IPC.shareAdd, (_e, share: Omit<NetworkShare, "id">) => addNetworkShare(share));
  ipcMain.handle(IPC.shareUpdate, (_e, share: NetworkShare) => updateNetworkShare(share));
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

  ipcMain.handle(IPC.metadataScan, async (_e, itemIds?: string[]) => {
    const allItems = await getAllItems();
    const items = itemIds ? allItems.filter((i) => itemIds.includes(i.id)) : allItems;
    const { networkShares } = getSettings();
    const found = await scanMetadataForLibrary(items, networkShares);
    return applyMetadata(found);
  });

  ipcMain.handle(
    IPC.itemUpdateMetadata,
    async (_e, itemId: string, edits: { title?: string; year?: string; tags?: string[]; artworkUrl?: string }) => {
      const items = await getAllItems();
      const item = items.find((i) => i.id === itemId);
      if (!item) throw new Error("Item not found — try rescanning.");

      const { networkShares } = getSettings();
      const existingSidecar = (await readSidecar(item, networkShares).catch(() => null)) ?? {};
      const mergedSidecar = {
        ...existingSidecar,
        title: edits.title !== undefined ? edits.title || undefined : existingSidecar.title,
        year: edits.year !== undefined ? edits.year || undefined : existingSidecar.year,
        tags: edits.tags !== undefined ? edits.tags : existingSidecar.tags,
        artworkUrl: edits.artworkUrl !== undefined ? edits.artworkUrl || undefined : existingSidecar.artworkUrl,
        infoFetched: true
      };
      await writeSidecar(item, mergedSidecar, networkShares).catch(() => {
        // Best-effort: a read-only share or a transient SMB error shouldn't block the edit from
        // applying to this device's own database below.
      });

      return applyManualEdit(itemId, edits);
    }
  );

  ipcMain.handle(IPC.pickImageFile, async () => {
    const result = await dialog.showOpenDialog({
      properties: ["openFile"],
      filters: [{ name: "Images", extensions: ["png", "jpg", "jpeg", "gif", "webp", "bmp"] }]
    });
    if (result.canceled || result.filePaths.length === 0) return null;
    const filePath = result.filePaths[0];
    const ext = filePath.split(".").pop()?.toLowerCase() ?? "png";
    const mime = ext === "jpg" ? "jpeg" : ext;
    const buffer = await readFile(filePath);
    return `data:image/${mime};base64,${buffer.toString("base64")}`;
  });

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

  ipcMain.handle(IPC.networkDownload, async (_e, item: MediaItem) => {
    const source = item.source;
    if (source.type !== "network") throw new Error("Not a network item.");
    const { networkShares } = getSettings();
    const share = networkShares.find((s) => s.id === source.shareId);
    if (!share) throw new Error("Network share for this item is no longer configured.");

    const result = await dialog.showSaveDialog({ defaultPath: item.name });
    if (result.canceled || !result.filePath) return null;

    const relPath = relPathFromMediaPath(share, item.path);
    await downloadRawFile(share, relPath, result.filePath);
    return result.filePath;
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
