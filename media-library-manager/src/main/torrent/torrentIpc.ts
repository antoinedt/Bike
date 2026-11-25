import { app, BrowserWindow, dialog, ipcMain, Notification, shell } from "electron";
import Store from "electron-store";
import { promises as fs } from "node:fs";
import path from "node:path";
import { IPC } from "../../shared/ipc.js";
import { replaceItemsForSource } from "../db.js";
import { scanLocalFolders } from "../library/scanner.js";
import { getSettings } from "../settings.js";
import { TorrentManager, type StoredTorrent } from "./torrentManager.js";

const store = new Store<{ torrents: StoredTorrent[] }>({
  name: "media-library.torrents",
  defaults: { torrents: [] }
});

function downloadFolder(): string {
  return getSettings().torrentDownloadFolder || path.join(app.getPath("downloads"), "Media Library Manager");
}

function broadcast(channel: string, payload?: unknown): void {
  for (const win of BrowserWindow.getAllWindows()) win.webContents.send(channel, payload);
}

function isInside(child: string, parent: string): boolean {
  const relative = path.relative(path.resolve(parent), path.resolve(child));
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
}

/** A finished download inside a library folder shows up in the Library without a manual rescan. */
async function onComplete(torrent: StoredTorrent): Promise<void> {
  if (Notification.isSupported()) {
    new Notification({ title: "Download finished", body: torrent.name }).show();
  }
  const { localFolders } = getSettings();
  if (!localFolders.some((folder) => isInside(torrent.savePath, folder.path))) return;
  const items = await scanLocalFolders(localFolders);
  await replaceItemsForSource((item) => item.source.type === "local", items);
  broadcast(IPC.libraryChanged);
}

export function registerTorrentHandlers(): void {
  const manager = new TorrentManager(
    { load: () => store.get("torrents"), save: (torrents) => store.set("torrents", torrents) },
    { onComplete: (torrent) => void onComplete(torrent).catch((err) => console.error("[torrent] after-download rescan failed:", err)) }
  );
  manager.restore();

  // Push progress to the Downloads page once a second while anything is downloading (and once
  // more after, so the final state shows).
  let wasActive = false;
  setInterval(() => {
    const active = manager.hasActive();
    if (active || wasActive) broadcast(IPC.torrentUpdate, manager.list());
    wasActive = active;
  }, 1000);

  app.on("before-quit", () => {
    void manager.shutdown();
  });

  ipcMain.handle(IPC.torrentDownloadFolder, () => downloadFolder());
  ipcMain.handle(IPC.torrentList, () => manager.list());
  ipcMain.handle(IPC.torrentAdd, async (_e, source: string) => {
    const status = await manager.add(source, downloadFolder());
    broadcast(IPC.torrentUpdate, manager.list());
    return status;
  });
  ipcMain.handle(IPC.torrentAddFile, async () => {
    const result = await dialog.showOpenDialog({
      properties: ["openFile"],
      filters: [{ name: "Torrent files", extensions: ["torrent"] }]
    });
    if (result.canceled || result.filePaths.length === 0) return null;
    const bytes = await fs.readFile(result.filePaths[0]);
    const status = await manager.add(new Uint8Array(bytes), downloadFolder());
    broadcast(IPC.torrentUpdate, manager.list());
    return status;
  });
  ipcMain.handle(IPC.torrentPause, (_e, id: string) => {
    manager.pause(id);
    return manager.list();
  });
  ipcMain.handle(IPC.torrentResume, (_e, id: string) => {
    manager.resume(id);
    return manager.list();
  });
  ipcMain.handle(IPC.torrentRemove, async (_e, id: string, deleteFiles: boolean) => {
    await manager.remove(id, deleteFiles);
    return manager.list();
  });
  ipcMain.handle(IPC.torrentOpenFolder, async (_e, id: string) => {
    const target = manager.pathFor(id);
    if (target && (await fs.stat(target).catch(() => null))) {
      shell.showItemInFolder(target);
    } else {
      const status = manager.list().find((t) => t.id === id);
      await shell.openPath(status?.savePath ?? downloadFolder());
    }
  });
}
