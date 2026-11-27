import "./appPaths.js";
import electronRuntime, { app, BrowserWindow, shell } from "electron";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { registerIpcHandlers } from "./ipc.js";
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

function summarizeWidevine(comps: unknown): string {
  try {
    const list = Array.isArray(comps) ? comps : [comps];
    const wv = list.find((c) => /widevine/i.test(JSON.stringify(c)));
    const text = JSON.stringify(wv ?? comps);
    return `Widevine component: ${text.length > 300 ? text.slice(0, 300) + "…" : text}`;
  } catch {
    return "Widevine component ready (details unavailable).";
  }
}

app.whenReady()
  .then(async () => {
    if (!widevine) {
      setWidevineStatus("This build has no Castlabs components API — it isn't the Widevine-enabled Electron.");
      return;
    }
    try {
      const comps = await widevine.whenReady();
      setWidevineStatus(summarizeWidevine(comps));
    } catch (e) {
      setWidevineStatus(`Widevine component failed to install: ${(e as Error)?.message ?? String(e)}`);
    }
  })
  .then(() => {
    registerIpcHandlers();
    createWindow();

    app.on("activate", () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
