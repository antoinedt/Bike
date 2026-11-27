import { BrowserWindow, session } from "electron";
import { log } from "../log.js";

/**
 * Looks like plain Chrome: some sites (or the CDN in front of them) refuse or stall an unknown
 * browser name, and the old "Media Library Manager" identity was exactly that.
 */
function browserUserAgent(): string {
  return session.defaultSession
    .getUserAgent()
    .replace(/\s(Electron|media-library-manager|OmniPlay)\/\S+/gi, "");
}

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

  // The page's console errors ("Failed to load resource: net::ERR_…", script errors) and failed
  // frame loads, for the log when results never show up. (Watching requests through
  // session.webRequest instead stops these pages from loading at all.)
  const problems: string[] = [];
  win.webContents.on("console-message", (event) => {
    const e = event as unknown as { level?: string; message?: string };
    if (e.level === "error" || e.level === "warning") problems.push(`${e.level}: ${String(e.message).slice(0, 160)}`);
  });
  win.webContents.on("did-fail-load", (_e, code, description, failedUrl) => {
    if (code !== -3) problems.push(`load failed ${description} ${failedUrl.slice(0, 120)}`);
  });
  try {
    win.webContents.setUserAgent(browserUserAgent());

    // Don't wait for the page's "load" event: busy sites (ads, trackers) may take ages to fire
    // it, or never do, even though the results are on screen within a second or two. Start
    // loading and poll for the results instead.
    let loadError = null as Error | null;
    win.loadURL(url).catch((err: Error) => {
      // ERR_ABORTED just means the page navigated on its own (redirects); not a failure.
      if (!/ERR_ABORTED/.test(err.message)) loadError = err;
    });

    const found = await waitForSelector(win, readySelector, timeoutMs, () => loadError);
    if (!found) {
      const failed = problems;
      log("search", `results never appeared on ${url}${loadError ? ` (page failed: ${loadError.message})` : ""}; ` +
        (failed.length ? `page errors: ${failed.slice(0, 10).join(" | ")}` : "the page reported no errors"));
      if (loadError) throw loadError;
    }

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
