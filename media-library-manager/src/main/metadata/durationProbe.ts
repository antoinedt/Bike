import { BrowserWindow } from "electron";

/**
 * Reads a media file's duration via a real <video> element (the `loadedmetadata` event) instead
 * of bundling ffmpeg/ffprobe — Chromium already parses container headers for every format this
 * app recognizes. `webSecurity` is off only on this throwaway, never-shown probing window (it
 * never loads remote/untrusted content — local file:// URLs or our own loopback stream server)
 * so it can load file:// media directly; network items are probed through the existing stream
 * server instead, which is plain http and works either way.
 */
export async function probeDurationSeconds(mediaUrl: string, timeoutMs = 15000): Promise<number | null> {
  const win = new BrowserWindow({
    show: false,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: false
    }
  });
  try {
    await win.loadURL("data:text/html,<video id=v></video>");
    const duration = await win.webContents.executeJavaScript(`
      new Promise((resolve) => {
        const v = document.getElementById("v");
        const timer = setTimeout(() => resolve(null), ${timeoutMs});
        v.addEventListener("loadedmetadata", () => {
          clearTimeout(timer);
          resolve(Number.isFinite(v.duration) ? v.duration : null);
        });
        v.addEventListener("error", () => {
          clearTimeout(timer);
          resolve(null);
        });
        v.src = ${JSON.stringify(mediaUrl)};
      });
    `);
    return typeof duration === "number" ? duration : null;
  } catch {
    return null;
  } finally {
    win.destroy();
  }
}
