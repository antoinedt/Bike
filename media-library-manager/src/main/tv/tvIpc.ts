import { BrowserWindow, ipcMain, safeStorage, session } from "electron";
import Store from "electron-store";
import { getCastController } from "../cast/castIpc.js";
import { getWidevineStatus } from "../widevineState.js";
import { IPC } from "../../shared/ipc.js";
import type { TvResult, TvStatus } from "../../shared/types.js";
import { FIZZ_CONFIG, FizzTvClient, randomDeviceId, SignInRequiredError, type BrowserSignIn, type TvCredentials, type TvCredentialStore } from "./fizzTv.js";

interface SavedTv {
  deviceId?: string;
  /** TvCredentials as JSON, encrypted with the OS key store when available (DPAPI on Windows). */
  credentials?: string;
  encrypted?: boolean;
}

const store = new Store<SavedTv>({ name: "media-library.tv" });

const credentialStore: TvCredentialStore = {
  load() {
    const raw = store.get("credentials");
    if (!raw) return null;
    try {
      const text = store.get("encrypted") ? safeStorage.decryptString(Buffer.from(raw, "base64")) : raw;
      return JSON.parse(text) as TvCredentials;
    } catch {
      return null;
    }
  },
  save(credentials) {
    if (!credentials) {
      store.delete("credentials");
      store.delete("encrypted");
      return;
    }
    const text = JSON.stringify(credentials);
    if (safeStorage.isEncryptionAvailable()) {
      store.set({ credentials: safeStorage.encryptString(text).toString("base64"), encrypted: true });
    } else {
      store.set({ credentials: text, encrypted: false });
    }
  },
  deviceId() {
    let id = store.get("deviceId");
    if (!id) {
      id = randomDeviceId();
      store.set("deviceId", id);
    }
    return id;
  }
};

// MLM_FIZZ_CONFIG (JSON) points the tab at a test server; normal use never sets it.
const client = new FizzTvClient(credentialStore, process.env.MLM_FIZZ_CONFIG ? { ...FIZZ_CONFIG, ...JSON.parse(process.env.MLM_FIZZ_CONFIG) } : FIZZ_CONFIG);

function status(): TvStatus {
  const saved = client.credentials();
  return { signedIn: !!saved, email: saved?.email ?? null };
}

async function wrap<T>(work: () => Promise<T>): Promise<TvResult<T>> {
  try {
    return { ok: true, value: await work() };
  } catch (e) {
    const signInRequired = e instanceof SignInRequiredError;
    const code = (e as { cause?: { code?: string } })?.cause?.code;
    const offline = code === "ENOTFOUND" || code === "ECONNREFUSED" || code === "ETIMEDOUT" || (e as Error)?.name === "TimeoutError";
    const error = offline ? "Can't reach the TV service. Check your connection." : (e as Error)?.message || "Something went wrong.";
    return { ok: false, signInRequired, error };
  }
}

/**
 * Fizz's own sign-in page in a small window. It ends on fizztv://callback (meant for the Fizz app),
 * which is caught here and never actually loaded.
 */
function signInWindow(pending: BrowserSignIn): Promise<string> {
  return new Promise((resolve, reject) => {
    const parent = BrowserWindow.getFocusedWindow() ?? BrowserWindow.getAllWindows()[0];
    const win = new BrowserWindow({
      parent,
      modal: !!parent,
      width: 480,
      height: 760,
      title: "Sign in to Fizz",
      autoHideMenuBar: true,
      webPreferences: {
        // Its own in-memory session: nothing from the sign-in page sticks around.
        session: session.fromPartition("fizz-signin"),
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true
      }
    });
    let settled = false;
    const finish = (url: string | null, error?: Error) => {
      if (settled) return;
      settled = true;
      if (url) resolve(url);
      else reject(error ?? new Error("Sign-in was cancelled."));
      if (!win.isDestroyed()) win.close();
    };
    const intercept = (event: { preventDefault(): void }, url: string) => {
      if (client.isRedirect(url)) {
        event.preventDefault();
        finish(url);
      }
    };
    win.webContents.on("will-redirect", intercept);
    win.webContents.on("will-navigate", intercept);
    win.webContents.on("did-fail-load", (_e, _code, _desc, url) => {
      if (client.isRedirect(url)) finish(url);
    });
    win.webContents.setWindowOpenHandler(({ url }) => {
      if (client.isRedirect(url)) finish(url);
      else void win.loadURL(url);
      return { action: "deny" };
    });
    win.on("closed", () => finish(null));
    void win.loadURL(pending.url).catch(() => {
      /* a fizztv:// redirect "fails" to load; handled above */
    });
  });
}

export function registerTvHandlers(): void {
  ipcMain.handle(IPC.tvStatus, () => status());
  ipcMain.handle(IPC.tvWidevineStatus, () => getWidevineStatus());
  ipcMain.handle(IPC.tvSignIn, () =>
    wrap(async () => {
      const pending = client.startBrowserSignIn();
      const redirect = await signInWindow(pending);
      await client.finishBrowserSignIn(redirect, pending);
      return status();
    })
  );
  ipcMain.handle(IPC.tvSignOut, () => client.signOut());
  ipcMain.handle(IPC.tvChannels, (_e, forceRefresh?: boolean) => wrap(() => client.myPlanChannels(!!forceRefresh)));
  ipcMain.handle(IPC.tvGuide, (_e, channelIds: number[], from: number, to: number) => wrap(() => client.guide(channelIds, from, to)));
  ipcMain.handle(IPC.tvStream, (_e, channelId: number) =>
    wrap(async () => {
      const stream = await client.streamForChannel(channelId);
      const kid = stream.drmToken ? await fetchDefaultKid(stream.url) : null;
      return { url: stream.url, isDash: stream.isDash, drmToken: stream.drmToken, licenseUrl: stream.licenseUrl, kid };
    })
  );
  // Cast a live channel to a Google TV via Fizz's own receiver (it does the Widevine playback).
  ipcMain.handle(IPC.tvCastLive, (_e, targetId: string, channelId: number, channelName: string) =>
    wrap(async () => {
      const stream = await client.streamForChannel(channelId);
      await getCastController().startLive(
        targetId,
        {
          url: stream.url,
          // Fizz sends video/mp4 and lets its receiver pick the parser from media_format_id.
          contentType: "video/mp4",
          mediaFormatId: stream.isDash ? 2 : 4,
          drmToken: stream.drmToken,
          drmLicenseUrl: stream.licenseUrl,
          drmType: stream.drmType,
          drmContentId: stream.drmContentId
        },
        channelName
      );
      return true;
    })
  );
}

/** Reads the DASH manifest's `default_kid` (what the Vualto licence envelope needs), like the Fizz app. */
async function fetchDefaultKid(manifestUrl: string): Promise<string | null> {
  try {
    const res = await fetch(manifestUrl, { signal: AbortSignal.timeout(15_000) });
    const text = await res.text();
    const match = /default_kid="(.*?)"/i.exec(text);
    return match ? match[1] : null;
  } catch {
    return null;
  }
}
