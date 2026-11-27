import "./appPaths.js";
import electronRuntime, { app, BrowserWindow, shell } from "electron";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { registerIpcHandlers } from "./ipc.js";
import { scanLibraryInBackground } from "./library/libraryScan.js";
import { setWidevineStatus } from "./widevineState.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const isDev = process.env.NODE_ENV === "development";

let mainWindow: BrowserWindow | null = null;

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 900,
    minHeight: 600,
    title: "OmniPlay",
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });

  // Anything the renderer tries to open externally goes through the OS handler, not a new
  // Electron window (e.g. a link clicked in a details view).
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    void shell.openExternal(url);
    return { action: "deny" };
  });

  if (isDev) {
    void mainWindow.loadURL("http://localhost:5173");
    mainWindow.webContents.openDevTools({ mode: "detach" });
  } else {
    void mainWindow.loadFile(path.join(__dirname, "../../dist/index.html"));
  }

  mainWindow.on("closed", () => {
    mainWindow = null;
  });
}

// The Castlabs Electron build ships Widevine (for live TV in the TV tab); wait for its
// component to be ready. Standard Electron has no `components`, so this is a no-op there.
const widevine = (electronRuntime as unknown as { components?: { whenReady(): Promise<unknown> } }).components;

// Log the Widevine component download/install (and CDM load) to a file users can send us when a
// protected channel won't play. Verbose only for the component/widevine/cdm modules to keep it small.
const widevineLogPath = path.join(app.getPath("userData"), "omniplay-widevine.log");
if (widevine) {
  app.commandLine.appendSwitch("enable-logging");
  app.commandLine.appendSwitch("log-file", widevineLogPath);
  app.commandLine.appendSwitch("vmodule", "*component_updater*=2,*widevine*=2,*cdm*=2,*component_patcher*=2");
}

function summarizeWidevine(comps: unknown): string {
  try {
    const list = Array.isArray(comps) ? comps : [comps];
    const wv = list.find((c) => /widevine/i.test(JSON.stringify(c)));
    const text = JSON.stringify(wv ?? comps);
    return `Widevine component ready: ${text.length > 300 ? text.slice(0, 300) + "…" : text}`;
  } catch {
    return "Widevine component ready (details unavailable).";
  }
}

function errorDetail(e: unknown): string {
  const err = e as (Error & Record<string, unknown>) | undefined;
  const parts = [err?.message ?? String(e)];
  try {
    const extra = JSON.stringify(err, Object.getOwnPropertyNames(err ?? {}));
    if (extra && extra !== "{}" && extra !== `{"message":${JSON.stringify(err?.message)}}`) parts.push(extra.slice(0, 300));
  } catch {
    /* ignore */
  }
  return parts.join(" :: ");
}

// Retry across a few attempts — the first launch's download can fail transiently (or while a VPN
// kill switch is still blocking) and then succeed moments later.
async function initWidevine(): Promise<void> {
  if (!widevine) {
    setWidevineStatus("This build has no Castlabs components API — it isn't the Widevine-enabled Electron.");
    return;
  }
  let lastError: unknown;
  // The component updater's first cycle is ~60s after start, so keep trying past that.
  for (let attempt = 1; attempt <= 8; attempt++) {
    try {
      const comps = await widevine.whenReady();
      setWidevineStatus(summarizeWidevine(comps));
      return;
    } catch (e) {
      lastError = e;
      await new Promise((resolve) => setTimeout(resolve, 15000));
    }
  }
  setWidevineStatus(`Widevine component install never reported ready (~2 min): ${errorDetail(lastError)}. If the hardware CDM is present the channel may still play. Log: ${widevineLogPath}`);
}

app.whenReady()
  .then(() => {
    // Install the CDM in the background; the hardware (Media Foundation) Widevine CDM is usually
    // ready immediately, so don't make the window wait on whenReady() (it can reject for ~60s).
    void initWidevine();
    registerIpcHandlers();
    createWindow();
    // Refresh the library in the background: it shows what was there last time straight away
    // and fills in / drops items as every folder and share is re-read in parallel.
    scanLibraryInBackground();

    app.on("activate", () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
