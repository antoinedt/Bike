import { BrowserWindow } from "electron";

/**
 * Grabs a real frame from a video as its "artwork" — a far better fallback than any online
 * lookup for a home media library, where most files (camera footage, home videos) will never
 * have a match in a commercial catalog like iTunes, let alone a dedicated poster. Uses the same
 * hidden-<video>-element technique as durationProbe.ts (and the same webSecurity-off, never-shown
 * probing window), then draws the frame to a <canvas> and reads it back as a data: URL — no
 * network request, no scraping, nothing that could break from a third party changing their site.
 */
export async function extractVideoThumbnail(mediaUrl: string, timeoutMs = 15000): Promise<string | null> {
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
    await win.loadURL("data:text/html,<video id=v></video><canvas id=c></canvas>");
    const dataUrl: string | null = await win.webContents.executeJavaScript(`
      new Promise((resolve) => {
        const v = document.getElementById("v");
        const timer = setTimeout(() => resolve(null), ${timeoutMs});
        function fail() { clearTimeout(timer); resolve(null); }
        v.addEventListener("error", fail);
        v.addEventListener("loadedmetadata", () => {
          if (!Number.isFinite(v.duration) || v.duration <= 0) return fail();
          // A bit into the clip avoids black/blank opening frames common in home videos.
          v.currentTime = Math.min(v.duration * 0.1, 10);
        });
        v.addEventListener("seeked", () => {
          try {
            const c = document.getElementById("c");
            c.width = v.videoWidth;
            c.height = v.videoHeight;
            if (!c.width || !c.height) return fail();
            c.getContext("2d").drawImage(v, 0, 0, c.width, c.height);
            clearTimeout(timer);
            resolve(c.toDataURL("image/jpeg", 0.8));
          } catch {
            fail();
          }
        });
        v.src = ${JSON.stringify(mediaUrl)};
      });
    `);
    return dataUrl;
  } catch {
    return null;
  } finally {
    win.destroy();
  }
}
