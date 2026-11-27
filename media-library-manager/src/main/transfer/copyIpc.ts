import { BrowserWindow, dialog, ipcMain } from "electron";
import path from "node:path";
import { IPC } from "../../shared/ipc.js";
import type { CopyTarget, MediaItem } from "../../shared/types.js";
import { scanLibrary } from "../library/libraryScan.js";
import { getSettings } from "../settings.js";
import { CopyManager } from "./copyManager.js";

function broadcast(channel: string, payload?: unknown): void {
  for (const win of BrowserWindow.getAllWindows()) win.webContents.send(channel, payload);
}

function isInside(child: string, parent: string): boolean {
  const relative = path.relative(path.resolve(parent), path.resolve(child));
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
}

/** The copy landed somewhere the Library covers: rescan that part so it shows up. */
async function rescanFor(target: CopyTarget): Promise<void> {
  const { localFolders } = getSettings();
  if (target.kind === "share") {
    await scanLibrary("network");
  } else if (target.kind === "folder" || localFolders.some((f) => isInside(target.path, f.path))) {
    await scanLibrary("local");
  }
}

export function registerCopyHandlers(): void {
  const manager = new CopyManager({
    settings: getSettings,
    onChange: (jobs) => broadcast(IPC.copyUpdate, jobs),
    onCopied: (job) => void rescanFor(job.target).catch((err) => console.error("[copy] rescan failed:", err))
  });

  ipcMain.handle(IPC.copyStart, (_e, item: MediaItem, target: CopyTarget) => manager.enqueue(item, target));
  ipcMain.handle(IPC.copyList, () => manager.list());
  ipcMain.handle(IPC.copyCancel, (_e, id: string) => manager.cancel(id));
  ipcMain.handle(IPC.copyClearFinished, () => {
    manager.clearFinished();
    return manager.list();
  });
  ipcMain.handle(IPC.copyListFolders, (_e, target: CopyTarget) => manager.listFolders(target));
  ipcMain.handle(IPC.copyCreateFolder, (_e, target: CopyTarget, name: string) => manager.createFolder(target, name));
  ipcMain.handle(IPC.copyPickFolder, async () => {
    const result = await dialog.showOpenDialog({ properties: ["openDirectory", "createDirectory"] });
    return result.canceled || result.filePaths.length === 0 ? null : result.filePaths[0];
  });
}
