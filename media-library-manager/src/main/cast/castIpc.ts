import { app, BrowserWindow, ipcMain } from "electron";
import { IPC } from "../../shared/ipc.js";
import type { MediaItem } from "../../shared/types.js";
import { getSettings } from "../settings.js";
import { CastController } from "./castController.js";
import { stopMediaServer } from "./mediaServer.js";

function broadcast(channel: string, payload?: unknown): void {
  for (const win of BrowserWindow.getAllWindows()) win.webContents.send(channel, payload);
}

export function registerCastHandlers(): void {
  const controller = new CastController(getSettings, (state) => broadcast(IPC.castState, state));
  ipcMain.handle(IPC.castDiscover, () => controller.discover());
  ipcMain.handle(IPC.castStart, (_e, targetId: string, item: MediaItem) => controller.start(targetId, item));
  ipcMain.handle(IPC.castControl, (_e, action: "play" | "pause" | "seek", value?: number) => controller.control(action, value));
  ipcMain.handle(IPC.castStop, () => controller.stop());
  ipcMain.handle(IPC.castGetState, () => controller.current());
  // The TV streams from this app, so casting ends with it.
  app.on("before-quit", () => {
    void controller.stop().finally(stopMediaServer);
  });
}
