import { app, BrowserWindow, ipcMain } from "electron";
import { spawn } from "node:child_process";
import { createWriteStream } from "node:fs";
import { rm, stat } from "node:fs/promises";
import { join } from "node:path";
import { IPC } from "../../shared/ipc.js";
import type { UpdateStatus } from "../../shared/types.js";

/**
 * Self-update from this repo's GitHub releases. Every Windows CI build is published as
 * `mlm-win-build-<N>` with the installer attached, and the app's version is `0.2.<N>`, so
 * "newer" just means a higher N. At launch: check, download the installer in the background,
 * then the app offers to restart (or installs when you next quit) — the NSIS installer runs
 * silently (`/S`) over the installed app, keeping settings and library.
 */
const RELEASES_URL = "https://api.github.com/repos/antoinedt/Bike/releases?per_page=100";
const TAG = /^mlm-win-build-(\d+)$/;
const ASSET = "media-library-manager-setup.exe";

let status: UpdateStatus = { state: "idle", current: app.getVersion() };
let installerPath: string | null = null;
let installOnQuit = false;
let checking: Promise<void> | null = null;

function setStatus(next: Partial<UpdateStatus>): void {
  status = { ...status, ...next };
  for (const win of BrowserWindow.getAllWindows()) win.webContents.send(IPC.updateStatus, status);
}

/** The build number in a "0.2.<N>" version; 0 for anything else. */
export function buildOf(version: string): number {
  const n = Number(version.split(".")[2]);
  return Number.isFinite(n) ? n : 0;
}

interface Release {
  tag_name: string;
  draft: boolean;
  assets: { name: string; size: number; browser_download_url: string }[];
}

async function latestRelease(): Promise<{ build: number; url: string; size: number } | null> {
  const res = await fetch(RELEASES_URL, { headers: { Accept: "application/vnd.github+json", "User-Agent": "OmniPlay" } });
  if (!res.ok) throw new Error(`GitHub answered ${res.status}`);
  const releases = (await res.json()) as Release[];
  let best: { build: number; url: string; size: number } | null = null;
  for (const r of releases) {
    const m = TAG.exec(r.tag_name);
    const asset = r.assets.find((a) => a.name === ASSET);
    if (!m || r.draft || !asset) continue;
    const build = Number(m[1]);
    if (!best || build > best.build) best = { build, url: asset.browser_download_url, size: asset.size };
  }
  return best;
}

async function download(url: string, size: number, dest: string): Promise<void> {
  const res = await fetch(url, { headers: { "User-Agent": "OmniPlay" } });
  if (!res.ok || !res.body) throw new Error(`Download failed (${res.status})`);
  const out = createWriteStream(dest);
  let received = 0;
  let lastSent = 0;
  try {
    const reader = res.body.getReader();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      received += value.length;
      if (!out.write(value)) await new Promise<void>((resolve) => out.once("drain", () => resolve()));
      const progress = size > 0 ? received / size : 0;
      if (progress - lastSent >= 0.02) {
        lastSent = progress;
        setStatus({ progress });
      }
    }
  } finally {
    await new Promise<void>((resolve, reject) => out.end((err?: Error | null) => (err ? reject(err) : resolve())));
  }
  if (size > 0 && (await stat(dest)).size !== size) throw new Error("The download was incomplete.");
}

/** Checks GitHub and, when there's a newer build, downloads it (state "ready" when done). */
export function checkForUpdate(): Promise<void> {
  if (checking) return checking;
  if (status.state === "ready" || status.state === "installing") return Promise.resolve();
  checking = (async () => {
    setStatus({ state: "checking", error: undefined, progress: undefined });
    try {
      const latest = await latestRelease();
      const current = buildOf(app.getVersion());
      if (!latest || latest.build <= current) {
        setStatus({ state: "up-to-date", latest: latest ? `0.2.${latest.build}` : undefined });
        return;
      }
      const version = `0.2.${latest.build}`;
      setStatus({ state: "downloading", latest: version, progress: 0 });
      const dest = join(app.getPath("temp"), `omniplay-setup-${version}.exe`);
      await rm(dest, { force: true });
      await download(latest.url, latest.size, dest);
      installerPath = dest;
      setStatus({ state: "ready", progress: 1 });
    } catch (err) {
      setStatus({ state: "error", error: (err as Error).message });
    } finally {
      checking = null;
    }
  })();
  return checking;
}

function runInstaller(relaunch: boolean): void {
  if (!installerPath) return;
  // Same flags electron-updater uses: /S = silent, --updated = an update (keeps app data),
  // --force-run = start the app again once installed.
  const args = relaunch ? ["/S", "--updated", "--force-run"] : ["/S", "--updated"];
  spawn(installerPath, args, { detached: true, stdio: "ignore" }).unref();
  installerPath = null;
}

export function registerUpdateHandlers(): void {
  ipcMain.handle(IPC.updateGetStatus, () => status);
  ipcMain.handle(IPC.updateCheck, () => checkForUpdate().then(() => status));
  ipcMain.handle(IPC.updateInstallNow, () => {
    if (!installerPath) return;
    setStatus({ state: "installing" });
    runInstaller(true);
    app.quit();
  });
  ipcMain.handle(IPC.updateLater, () => {
    installOnQuit = true;
  });
  app.on("will-quit", () => {
    if (installOnQuit && installerPath) runInstaller(false);
  });
}

/** At launch, a little after the window is up. Only the installed Windows app updates itself. */
export function checkForUpdateAtLaunch(): void {
  if (!app.isPackaged || process.platform !== "win32") return;
  setTimeout(() => void checkForUpdate(), 8000);
}
