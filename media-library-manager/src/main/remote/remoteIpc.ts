import { BrowserWindow, ipcMain, Notification } from "electron";
import { IPC } from "../../shared/ipc.js";
import type { RemoteDeviceInfo } from "../../shared/types.js";
import { getSettings, updateRemoteSettings } from "../settings.js";
import { addTorrentHere } from "../torrent/torrentIpc.js";
import { resolveSource } from "../torrent/torrentManager.js";
import { connectShare } from "../transfer/smbWrite.js";
import { smbMailbox } from "./smbMailbox.js";
import { MAILBOX_ROOT, ONLINE_MS, RemoteQueue, magnetName, type RemoteResult } from "./remoteQueue.js";

const POLL_MS = 8_000;
const HEARTBEAT_MS = 60_000;

function broadcast(channel: string, payload?: unknown): void {
  for (const win of BrowserWindow.getAllWindows()) win.webContents.send(channel, payload);
}

function notify(title: string, body: string): void {
  if (Notification.isSupported()) new Notification({ title, body }).show();
}

/** Runs `fn` against the configured mailbox share, or returns null when the feature is off. */
async function withQueue<T>(fn: (queue: RemoteQueue) => Promise<T>): Promise<T | null> {
  const settings = getSettings();
  const share = settings.networkShares.find((s) => s.id === settings.remoteShareId);
  if (!share) return null;
  const client = connectShare(share);
  try {
    const queue = new RemoteQueue(smbMailbox(client, share), { id: settings.deviceId, name: settings.deviceName, platform: "windows" }, {
      onJob: async (job) => {
        const source = job.magnet ?? Buffer.from(job.torrent ?? "", "base64");
        const added = await addTorrentHere(source);
        notify("Download started", `${added.name} — sent from ${job.fromName}`);
        broadcast(IPC.remoteEvent, `Started "${added.name}" (sent from ${job.fromName}).`);
        return { ok: true, message: "Downloading", name: added.name };
      },
      onResult: (result: RemoteResult) => {
        const text = result.ok
          ? `${result.toName} started downloading "${result.name}".`
          : `${result.toName} couldn't start "${result.name}": ${result.message}`;
        broadcast(IPC.remoteEvent, text);
        notify(result.ok ? "Download started on another device" : "Download not started", text);
      }
    });
    return await fn(queue);
  } finally {
    client.close();
  }
}

export function registerRemoteHandlers(): void {
  let lastHeartbeat = 0;
  let busy = false;
  const tick = async () => {
    if (busy) return;
    busy = true;
    try {
      await withQueue(async (queue) => {
        if (Date.now() - lastHeartbeat > HEARTBEAT_MS) {
          await queue.heartbeat();
          lastHeartbeat = Date.now();
        }
        await queue.poll();
      });
    } catch (err) {
      console.error("[remote] poll failed:", (err as Error).message);
    } finally {
      busy = false;
    }
  };
  setInterval(() => void tick(), POLL_MS);
  setTimeout(() => void tick(), 2_000);

  ipcMain.handle(IPC.remoteUpdateSettings, async (_e, deviceName: string, remoteShareId: string) => {
    updateRemoteSettings(deviceName, remoteShareId);
    lastHeartbeat = 0;
    await tick();
  });

  ipcMain.handle(IPC.remoteDevices, async (): Promise<RemoteDeviceInfo[]> => {
    const devices = await withQueue((queue) => queue.devices());
    if (devices === null) throw new Error("Pick a network share for “Send to another device” in Settings first.");
    const now = Date.now();
    return devices.map((d) => ({ ...d, online: now - d.lastSeen < ONLINE_MS }));
  });

  /** Fetches the .torrent here (this computer may have site access the other device doesn't), then sends it. */
  ipcMain.handle(IPC.remoteSend, async (_e, deviceId: string, source: string, title?: string) => {
    const resolved = await resolveSource(source);
    const payload =
      typeof resolved === "string"
        ? { magnet: resolved, name: title || magnetName(resolved) || "Download" }
        : { torrent: resolved, name: title || "Download" };
    const sent = await withQueue((queue) => queue.send(deviceId, payload));
    if (!sent) throw new Error("Pick a network share for “Send to another device” in Settings first.");
    return sent.name;
  });
}

export { MAILBOX_ROOT };
