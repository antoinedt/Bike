import { BrowserWindow } from "electron";

const USER_AGENT =
  "Mozilla/5.0 (Media Library Manager; +desktop app) media-library-manager/0.1";

/**
 * Loads a URL in a hidden BrowserWindow — Electron's own Chromium, so no extra headless-browser
 * dependency is needed — so sites that render their results with JavaScript actually finish
 * rendering before we scrape them. Waits until `readySelector` matches something in the DOM (or
 * gives up after a bounded number of polls — deliberately not an error: a search with zero real
 * results will legitimately never match, and that's a valid outcome, not a failure), then
 * returns the fully-rendered HTML plus the final URL (for relative-link resolution after any
 * redirects).
 */
export async function fetchRenderedHtml(
  url: string,
  readySelector: string,
  timeoutMs = 15000
): Promise<{ html: string; finalUrl: string }> {
  const win = new BrowserWindow({
    show: false,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });

  try {
    win.webContents.setUserAgent(USER_AGENT);

    const loadPromise = win.loadURL(url);
    const loadTimeout = new Promise<never>((_, reject) =>
      setTimeout(() => reject(new Error(`Timed out loading ${url}`)), timeoutMs)
    );
    await Promise.race([loadPromise, loadTimeout]);

    await waitForSelector(win, readySelector, timeoutMs);

    const html: string = await win.webContents.executeJavaScript("document.documentElement.outerHTML");
    const finalUrl = win.webContents.getURL();
    return { html, finalUrl };
  } finally {
    win.destroy();
  }
}

async function waitForSelector(win: BrowserWindow, selector: string, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  const escaped = JSON.stringify(selector);
  while (Date.now() < deadline) {
    const count: number = await win.webContents
      .executeJavaScript(`document.querySelectorAll(${escaped}).length`)
      .catch(() => 0);
    if (count > 0) return;
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
}
