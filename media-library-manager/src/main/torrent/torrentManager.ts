import { promises as fs } from "node:fs";
import path from "node:path";
import WebTorrent, { type Torrent, type WebTorrentOptions } from "webtorrent";
import parseTorrent, { toMagnetURI } from "parse-torrent";
import type { TorrentStatus } from "../../shared/types.js";

const USER_AGENT = "Mozilla/5.0 (Media Library Manager; +desktop app) media-library-manager/0.1";
const MAX_TORRENT_FILE_BYTES = 10 * 1024 * 1024;

/** What's remembered about each download across app restarts. */
export interface StoredTorrent {
  /** Info hash. */
  id: string;
  name: string;
  /** Magnet link — always available, even before the file list is known. */
  magnetURI: string;
  /** The .torrent file (base64) once known, so a resume doesn't have to fetch it from peers again. */
  torrentFile?: string;
  savePath: string;
  length: number;
  /** Last known progress (0..1) and bytes, for paused/stopped torrents that aren't loaded. */
  progress: number;
  downloaded: number;
  paused: boolean;
  done: boolean;
  addedAt: number;
}

export interface TorrentPersistence {
  load(): StoredTorrent[];
  save(entries: StoredTorrent[]): void;
}

export interface TorrentManagerOptions {
  /** Passed to the WebTorrent client (tests turn off DHT/trackers). */
  clientOptions?: WebTorrentOptions;
  /** Called once when a download finishes. */
  onComplete?: (torrent: StoredTorrent) => void;
}

interface Entry {
  stored: StoredTorrent;
  /** The live WebTorrent torrent while downloading; null while paused, done or failed. */
  torrent: Torrent | null;
  error?: string;
}

const toBase64 = (bytes: Uint8Array) => Buffer.from(bytes).toString("base64");

/**
 * In-app torrent downloads (WebTorrent). Downloads stop once complete rather than seeding
 * indefinitely. Pausing unloads the torrent but keeps its files; resuming re-adds it, and
 * WebTorrent verifies what's already on disk and continues from there. Kept free of Electron APIs
 * so it can be tested in plain Node — persistence and the save folder are passed in.
 */
export class TorrentManager {
  private client: WebTorrent | null = null;
  private readonly entries = new Map<string, Entry>();

  constructor(
    private readonly persistence: TorrentPersistence,
    private readonly options: TorrentManagerOptions = {}
  ) {}

  private getClient(): WebTorrent {
    if (!this.client) {
      this.client = new WebTorrent(this.options.clientOptions);
      // Without a listener, an error here would take down the whole main process.
      this.client.on("error", (err: Error) => console.error("[torrent] client error:", err.message));
    }
    return this.client;
  }

  /** Reloads saved downloads and restarts the ones that were neither paused nor finished. */
  restore(): void {
    for (const stored of this.persistence.load()) {
      const entry: Entry = { stored, torrent: null };
      this.entries.set(stored.id, entry);
      if (!stored.paused && !stored.done) this.start(entry);
    }
  }

  /**
   * Adds a magnet link, a bare info hash, an http(s) URL to a .torrent file, or the contents of a
   * .torrent file. Adding something that's already in the list resumes it if paused.
   */
  async add(source: string | Uint8Array, savePath: string): Promise<TorrentStatus> {
    const input = typeof source === "string" ? await resolveSource(source) : source;
    let parsed;
    try {
      parsed = await parseTorrent(input);
    } catch {
      throw new Error("That isn't a valid magnet link or .torrent file.");
    }
    if (!parsed?.infoHash) throw new Error("That isn't a valid magnet link or .torrent file.");

    const existing = this.entries.get(parsed.infoHash);
    if (existing) {
      if (existing.stored.paused || existing.error) this.resume(existing.stored.id);
      return this.status(existing);
    }

    await fs.mkdir(savePath, { recursive: true });
    const stored: StoredTorrent = {
      id: parsed.infoHash,
      name: parsed.name || "",
      magnetURI: typeof input === "string" ? input : toMagnetURI(parsed),
      torrentFile: typeof input === "string" ? undefined : toBase64(input),
      savePath,
      length: parsed.length ?? 0,
      progress: 0,
      downloaded: 0,
      paused: false,
      done: false,
      addedAt: Date.now()
    };
    const entry: Entry = { stored, torrent: null };
    this.entries.set(stored.id, entry);
    this.start(entry);
    this.persist();
    return this.status(entry);
  }

  private start(entry: Entry): void {
    const { stored } = entry;
    entry.error = undefined;
    const torrentId = stored.torrentFile ? Buffer.from(stored.torrentFile, "base64") : stored.magnetURI;
    const torrent = this.getClient().add(torrentId, { path: stored.savePath });
    entry.torrent = torrent;

    torrent.on("metadata", () => {
      stored.name = torrent.name;
      stored.length = torrent.length;
      stored.torrentFile = toBase64(torrent.torrentFile);
      this.persist();
    });
    torrent.on("done", () => {
      stored.done = true;
      stored.progress = 1;
      stored.downloaded = stored.length;
      this.persist();
      // Finished: stop (no seeding). Deferred so WebTorrent finishes its own "done" handling first.
      setImmediate(() => {
        if (entry.torrent === torrent) {
          entry.torrent = null;
          torrent.destroy();
        }
        this.options.onComplete?.(stored);
      });
    });
    torrent.on("error", (err: Error | string) => {
      // WebTorrent destroys a torrent after an error; it has to be re-added to retry.
      entry.error = typeof err === "string" ? err : err.message;
      if (entry.torrent === torrent) entry.torrent = null;
    });
  }

  pause(id: string): void {
    const entry = this.entries.get(id);
    if (!entry || entry.stored.done) return;
    this.snapshotProgress(entry);
    entry.stored.paused = true;
    if (entry.torrent) {
      entry.torrent.destroy();
      entry.torrent = null;
    }
    this.persist();
  }

  resume(id: string): void {
    const entry = this.entries.get(id);
    if (!entry || entry.stored.done) return;
    entry.stored.paused = false;
    if (!entry.torrent) this.start(entry);
    this.persist();
  }

  /** Removes from the list; with [deleteFiles], also deletes what was downloaded. */
  async remove(id: string, deleteFiles: boolean): Promise<void> {
    const entry = this.entries.get(id);
    if (!entry) return;
    this.entries.delete(id);
    const torrent = entry.torrent;
    entry.torrent = null;
    if (torrent) await new Promise<void>((resolve) => torrent.destroy({}, () => resolve()));
    this.persist();
    if (deleteFiles) {
      const target = downloadedPath(entry.stored);
      if (target) await fs.rm(target, { recursive: true, force: true });
    }
  }

  /** The file (single-file torrent) or folder (multi-file) this download saves to; null until the name is known. */
  pathFor(id: string): string | null {
    const entry = this.entries.get(id);
    return entry ? downloadedPath(entry.stored) : null;
  }

  list(): TorrentStatus[] {
    return Array.from(this.entries.values())
      .map((entry) => this.status(entry))
      .sort((a, b) => b.addedAt - a.addedAt);
  }

  hasActive(): boolean {
    return Array.from(this.entries.values()).some((entry) => entry.torrent !== null);
  }

  /** Saves progress and stops the client (app quitting). */
  async shutdown(): Promise<void> {
    for (const entry of this.entries.values()) this.snapshotProgress(entry);
    this.persist();
    const client = this.client;
    this.client = null;
    if (client) await new Promise<void>((resolve) => client.destroy(() => resolve()));
  }

  private snapshotProgress(entry: Entry): void {
    const torrent = entry.torrent;
    if (torrent && torrent.length > 0) {
      entry.stored.progress = torrent.progress;
      entry.stored.downloaded = torrent.downloaded;
    }
  }

  private status(entry: Entry): TorrentStatus {
    const { stored, torrent } = entry;
    const live = torrent !== null && torrent.length > 0;
    const state: TorrentStatus["state"] = entry.error
      ? "error"
      : stored.done
        ? "done"
        : stored.paused
          ? "paused"
          : torrent && !torrent.ready
            ? "metadata"
            : "downloading";
    const timeRemaining = live && torrent.downloadSpeed > 0 && Number.isFinite(torrent.timeRemaining) ? torrent.timeRemaining : null;
    return {
      id: stored.id,
      name: stored.name || (live ? torrent.name : "") || "Getting file list…",
      state,
      progress: stored.done ? 1 : live ? torrent.progress : stored.progress,
      downloadSpeed: torrent ? torrent.downloadSpeed : 0,
      uploadSpeed: torrent ? torrent.uploadSpeed : 0,
      peers: torrent ? torrent.numPeers : 0,
      length: stored.length || (live ? torrent.length : 0),
      downloaded: stored.done ? stored.length : live ? torrent.downloaded : stored.downloaded,
      timeRemainingMs: state === "downloading" ? timeRemaining : null,
      savePath: stored.savePath,
      error: entry.error,
      addedAt: stored.addedAt
    };
  }

  private persist(): void {
    this.persistence.save(Array.from(this.entries.values()).map((entry) => ({ ...entry.stored })));
  }
}

/**
 * The download's own file/folder inside its save folder — guarded so a malformed name can never
 * point outside the save folder (this path gets deleted on "remove and delete files").
 */
function downloadedPath(stored: StoredTorrent): string | null {
  if (!stored.name) return null;
  const base = path.resolve(stored.savePath);
  const target = path.resolve(base, stored.name);
  if (target === base || !target.startsWith(base + path.sep)) return null;
  return target;
}

/** Magnet links and bare info hashes pass through; http(s) .torrent URLs are downloaded. */
export async function resolveSource(source: string): Promise<string | Uint8Array> {
  const value = source.trim();
  if (value.startsWith("magnet:")) return value;
  if (/^[0-9a-f]{40}$/i.test(value) || /^[a-z2-7]{32}$/i.test(value)) return `magnet:?xt=urn:btih:${value}`;
  if (!/^https?:\/\//i.test(value)) {
    throw new Error("Paste a magnet link, an info hash, or an http(s) link to a .torrent file.");
  }
  // Redirects are followed by hand: some sites redirect a "download" link to a magnet link, which
  // fetch can't follow on its own.
  let url = value;
  let res: Response | null = null;
  for (let hops = 0; hops < 5; hops++) {
    res = await fetch(url, { headers: { "User-Agent": USER_AGENT }, redirect: "manual" });
    const location = res.status >= 300 && res.status < 400 ? res.headers.get("location") : null;
    if (!location) break;
    if (location.startsWith("magnet:")) return location;
    url = new URL(location, url).toString();
    res = null;
  }
  if (!res) throw new Error("Too many redirects fetching the .torrent file.");
  if (!res.ok) throw new Error(`Couldn't download the .torrent file (HTTP ${res.status}).`);
  const bytes = new Uint8Array(await res.arrayBuffer());
  if (bytes.length > MAX_TORRENT_FILE_BYTES) throw new Error("That file is too large to be a .torrent file.");
  return bytes;
}
