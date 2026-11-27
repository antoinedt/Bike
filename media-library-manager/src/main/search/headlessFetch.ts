import { BrowserWindow } from "electron";

const USER_AGENT =
  "Mozilla/5.0 (Media Library Manager; +desktop app) media-library-manager/0.1";

/**
 * Loads a URL in a hidden BrowserWindow — Electron's own Chromium, so no extra headless-browser
 * dependency is needed — so sites that render their results with JavaScript actually finish
 * rendering before we scrape them. Waits until `readySelector` matches something in the DOM — not
 * for the page's load event, which ad-heavy sites may never fire — (or gives up after the timeout — deliberately not an error: a search with zero real
 * results will legitimately never match, and that's a valid outcome, not a failure), then
 * returns the fully-rendered HTML plus the final URL (for relative-link resolution after any
 * redirects).
 */
export async function fetchRenderedHtml(
  url: string,
  readySelector: string,
  timeoutMs = 20000
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

    // Don't wait for the page's "load" event: busy sites (ads, trackers) may take ages to fire
    // it, or never do, even though the results are on screen within a second or two. Start
    // loading and poll for the results instead.
    let loadError: Error | null = null;
    win.loadURL(url).catch((err: Error) => {
      // ERR_ABORTED just means the page navigated on its own (redirects); not a failure.
      if (!/ERR_ABORTED/.test(err.message)) loadError = err;
    });

    const found = await waitForSelector(win, readySelector, timeoutMs, () => loadError);
    if (!found && loadError) throw loadError;

    const html = String(await win.webContents.mainFrame.executeJavaScript("document.documentElement.outerHTML"));
    const finalUrl = win.webContents.getURL() || url;
    return { html, finalUrl };
  } finally {
    win.destroy();
  }
}

/** True once `selector` matches; false after the timeout, or as soon as loading failed. */
async function waitForSelector(
  win: BrowserWindow,
  selector: string,
  timeoutMs: number,
  failed: () => Error | null
): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  const escaped = JSON.stringify(selector);
  while (Date.now() < deadline) {
    // Through the main frame: webContents.executeJavaScript would wait for the page's load
    // event first, which on ad-heavy sites can take 20+ s after the results are already there.
    const count = Number(
      await win.webContents.mainFrame.executeJavaScript(`document.querySelectorAll(${escaped}).length`).catch(() => 0)
    );
    if (count > 0) return true;
    if (failed()) return false;
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  return false;
}
