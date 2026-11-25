import { randomUUID } from "node:crypto";

// "Send to another device": a mailbox in a hidden folder on a network share both devices use.
//
//   .media-library-manager/devices/<deviceId>.json        who's out there (refreshed while the app is open)
//   .media-library-manager/inbox/<deviceId>/<job>.json    downloads waiting for that device
//   .media-library-manager/results/<deviceId>/<job>.json  "started it" / "couldn't" replies to the sender
//
// The Android app speaks the same format (RemoteQueue.kt) — keep them in step. Files are written
// under a ".tmp" name and renamed when complete, so a reader never sees half a file.

export const MAILBOX_ROOT = ".media-library-manager";
/** A device counts as online if it checked in this recently. */
export const ONLINE_MS = 3 * 60 * 1000;

export interface Mailbox {
  /** File names in a folder ([] if it doesn't exist). */
  list(dir: string): Promise<string[]>;
  read(path: string): Promise<Buffer>;
  /** Creates parent folders as needed; overwrites. */
  write(path: string, data: Buffer): Promise<void>;
  rename(from: string, to: string): Promise<void>;
  remove(path: string): Promise<void>;
}

export interface RemoteDevice {
  id: string;
  name: string;
  platform: string;
  lastSeen: number;
}

export interface RemoteJob {
  id: string;
  from: string;
  fromName: string;
  /** One of these two. */
  magnet?: string;
  /** Base64 .torrent file. */
  torrent?: string;
  name: string;
  createdAt: number;
}

export interface RemoteResult {
  jobId: string;
  to: string;
  toName: string;
  ok: boolean;
  message: string;
  name: string;
  at: number;
}

export interface Me {
  id: string;
  name: string;
  platform: string;
}

const join = (...parts: string[]) => parts.join("/");

export class RemoteQueue {
  constructor(
    private mailbox: Mailbox,
    private me: Me,
    private handlers: {
      /** Start the download; resolve with what to tell the sender. */
      onJob: (job: RemoteJob) => Promise<{ ok: boolean; message: string; name?: string }>;
      onResult?: (result: RemoteResult) => void;
    }
  ) {}

  private async writeJson(path: string, value: unknown): Promise<void> {
    const tmp = `${path}.tmp`;
    await this.mailbox.write(tmp, Buffer.from(JSON.stringify(value, null, 2)));
    await this.mailbox.remove(path).catch(() => undefined);
    await this.mailbox.rename(tmp, path);
  }

  private async readJson<T>(path: string): Promise<T | null> {
    try {
      return JSON.parse((await this.mailbox.read(path)).toString("utf8")) as T;
    } catch {
      return null;
    }
  }

  /** Says "I'm here" so other devices list this one. */
  async heartbeat(now = Date.now()): Promise<void> {
    const device: RemoteDevice = { id: this.me.id, name: this.me.name, platform: this.me.platform, lastSeen: now };
    await this.writeJson(join(MAILBOX_ROOT, "devices", `${this.me.id}.json`), device);
  }

  /** Other devices that have checked in, most recently seen first. */
  async devices(): Promise<RemoteDevice[]> {
    const dir = join(MAILBOX_ROOT, "devices");
    const names = (await this.mailbox.list(dir)).filter((n) => n.endsWith(".json"));
    const devices: RemoteDevice[] = [];
    for (const name of names) {
      const d = await this.readJson<RemoteDevice>(join(dir, name));
      if (d && d.id && d.id !== this.me.id) devices.push(d);
    }
    return devices.sort((a, b) => b.lastSeen - a.lastSeen);
  }

  /** Puts a download in another device's inbox. */
  async send(toId: string, payload: { magnet?: string; torrent?: Uint8Array; name: string }, now = Date.now()): Promise<RemoteJob> {
    if (!payload.magnet && !payload.torrent) throw new Error("Nothing to send.");
    const job: RemoteJob = {
      id: randomUUID(),
      from: this.me.id,
      fromName: this.me.name,
      ...(payload.magnet ? { magnet: payload.magnet } : { torrent: Buffer.from(payload.torrent!).toString("base64") }),
      name: payload.name,
      createdAt: now
    };
    await this.writeJson(join(MAILBOX_ROOT, "inbox", toId, `${now}-${job.id}.json`), job);
    return job;
  }

  /** Starts anything waiting in this device's inbox, and picks up replies to what it sent. */
  async poll(): Promise<void> {
    const inbox = join(MAILBOX_ROOT, "inbox", this.me.id);
    for (const name of (await this.mailbox.list(inbox)).filter((n) => n.endsWith(".json")).sort()) {
      const path = join(inbox, name);
      const job = await this.readJson<RemoteJob>(path);
      // Take it out of the inbox first, so a crash can't start it twice.
      await this.mailbox.remove(path).catch(() => undefined);
      if (!job?.id || !job.from) continue;
      let outcome: { ok: boolean; message: string; name?: string };
      try {
        outcome = await this.handlers.onJob(job);
      } catch (err) {
        outcome = { ok: false, message: (err as Error).message };
      }
      const result: RemoteResult = {
        jobId: job.id,
        to: this.me.id,
        toName: this.me.name,
        ok: outcome.ok,
        message: outcome.message,
        name: outcome.name ?? job.name,
        at: Date.now()
      };
      await this.writeJson(join(MAILBOX_ROOT, "results", job.from, `${job.id}.json`), result).catch(() => undefined);
    }

    const results = join(MAILBOX_ROOT, "results", this.me.id);
    for (const name of (await this.mailbox.list(results)).filter((n) => n.endsWith(".json"))) {
      const path = join(results, name);
      const result = await this.readJson<RemoteResult>(path);
      await this.mailbox.remove(path).catch(() => undefined);
      if (result) this.handlers.onResult?.(result);
    }
  }
}

/** "magnet:?xt=…&dn=Name" → "Name". */
export function magnetName(magnet: string): string | undefined {
  const match = /[?&]dn=([^&]+)/.exec(magnet);
  if (!match) return undefined;
  try {
    return decodeURIComponent(match[1].replace(/\+/g, " "));
  } catch {
    return match[1];
  }
}

/** A mailbox in a plain folder (tests, and a share that's mounted as a drive). */
export function folderMailbox(root: string): Mailbox {
  // Imported lazily so the SMB path doesn't pull in fs promises it doesn't use.
  const fsp = import("node:fs/promises");
  const pathMod = import("node:path");
  const full = async (p: string) => (await pathMod).join(root, ...p.split("/"));
  return {
    list: async (dir) => (await fsp).readdir(await full(dir)).catch(() => []),
    read: async (p) => (await fsp).readFile(await full(p)),
    write: async (p, data) => {
      const target = await full(p);
      await (await fsp).mkdir((await pathMod).dirname(target), { recursive: true });
      await (await fsp).writeFile(target, data);
    },
    rename: async (from, to) => (await fsp).rename(await full(from), await full(to)),
    remove: async (p) => (await fsp).rm(await full(p))
  };
}
