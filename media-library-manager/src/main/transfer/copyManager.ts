import { createReadStream, createWriteStream, promises as fs } from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";
import type { CopyJob, CopyTarget, LocalFolder, MediaItem, NetworkShare } from "../../shared/types.js";
import { closeRawFile, openRawFile, readRawRange } from "../network/smbRawClient.js";
import { relPathFromMediaPath } from "../network/smbClient.js";
import {
  connectShare,
  sharePath,
  smbCloseFile,
  smbCreateUnique,
  smbDelete,
  smbListFolders,
  smbMkdirp,
  smbWriteAll
} from "./smbWrite.js";

// "Copy to…": copies a library item (local file or file on a share) into a folder on this computer
// or on a share, one at a time, in the background. Never overwrites. Electron-free, so it's
// tested with plain Node.

export interface CopyManagerDeps {
  settings: () => { localFolders: LocalFolder[]; networkShares: NetworkShare[] };
  /** Called whenever any job changes. */
  onChange?: (jobs: CopyJob[]) => void;
  /** Called after a successful copy, e.g. to rescan the folder it landed in. */
  onCopied?: (job: CopyJob) => void;
}

/** "Movie (2).mkv" for the 2nd attempt at "Movie.mkv". */
export function numberedName(name: string, n: number): string {
  if (n <= 1) return name;
  const ext = path.extname(name);
  return ext && ext !== name ? `${name.slice(0, -ext.length)} (${n})${ext}` : `${name} (${n})`;
}

export function cleanName(name: string): string {
  // eslint-disable-next-line no-control-regex
  const cleaned = name.replace(/[\\/:*?"<>|\x00-\x1f]/g, "_").trim().replace(/\.+$/, "");
  if (!cleaned || cleaned === "..") throw new Error(`"${name}" isn't a usable name.`);
  return cleaned;
}

function cleanRelPath(relPath: string): string {
  return relPath.split(/[\\/]/).filter(Boolean).map(cleanName).join("/");
}

const CHUNK = 1024 * 1024;

export class CopyManager {
  private jobs: CopyJob[] = [];
  private running = false;
  private cancelled = new Set<string>();

  constructor(private deps: CopyManagerDeps) {}

  list(): CopyJob[] {
    return this.jobs.map((j) => ({ ...j }));
  }

  hasActive(): boolean {
    return this.jobs.some((j) => j.state === "queued" || j.state === "running");
  }

  label(target: CopyTarget): string {
    const { localFolders, networkShares } = this.deps.settings();
    if (target.kind === "path") return target.path;
    const base =
      target.kind === "folder"
        ? localFolders.find((f) => f.id === target.folderId)?.label ?? "Removed folder"
        : networkShares.find((s) => s.id === target.shareId)?.label ?? "Removed share";
    return target.relPath ? `${base}/${target.relPath}` : base;
  }

  /** Starts copying `item` into `target` once earlier copies are done. */
  enqueue(item: MediaItem, target: CopyTarget): CopyJob {
    const job: CopyJob = {
      id: randomUUID(),
      itemId: item.id,
      itemName: item.name,
      target,
      targetLabel: this.label(target),
      state: "queued",
      copiedBytes: 0,
      totalBytes: item.sizeBytes
    };
    this.jobs.push(job);
    this.items.set(job.id, item);
    this.changed();
    void this.pump();
    return { ...job };
  }

  private items = new Map<string, MediaItem>();

  cancel(id: string): void {
    this.cancelled.add(id);
    const job = this.jobs.find((j) => j.id === id);
    if (job?.state === "queued") {
      job.state = "failed";
      job.error = "Cancelled.";
      this.changed();
    }
  }

  clearFinished(): void {
    this.jobs = this.jobs.filter((j) => j.state === "queued" || j.state === "running");
    this.changed();
  }

  /** Resolves once nothing is queued or running (for tests). */
  async idle(): Promise<void> {
    while (this.hasActive()) await new Promise((r) => setTimeout(r, 50));
  }

  async listFolders(target: CopyTarget): Promise<string[]> {
    if (target.kind === "share") {
      const share = this.share(target.shareId);
      const client = connectShare(share);
      try {
        return await smbListFolders(client, sharePath(share, target.relPath));
      } finally {
        client.close();
      }
    }
    const dir = this.localDir(target);
    const entries = await fs.readdir(dir, { withFileTypes: true });
    return entries
      .filter((e) => e.isDirectory() && !e.name.startsWith("."))
      .map((e) => e.name)
      .sort((a, b) => a.localeCompare(b, undefined, { sensitivity: "base" }));
  }

  async createFolder(target: CopyTarget, name: string): Promise<void> {
    const clean = cleanName(name);
    if (target.kind === "share") {
      const share = this.share(target.shareId);
      const client = connectShare(share);
      try {
        await smbMkdirp(client, sharePath(share, [target.relPath, clean].filter(Boolean).join("/")));
      } finally {
        client.close();
      }
      return;
    }
    await fs.mkdir(path.join(this.localDir(target), clean), { recursive: true });
  }

  private async pump(): Promise<void> {
    if (this.running) return;
    this.running = true;
    try {
      for (;;) {
        const job = this.jobs.find((j) => j.state === "queued");
        if (!job) break;
        await this.run(job);
      }
    } finally {
      this.running = false;
    }
  }

  private async run(job: CopyJob): Promise<void> {
    const item = this.items.get(job.id)!;
    job.state = "running";
    this.changed();
    let lastReport = 0;
    const progress = (copied: number) => {
      job.copiedBytes = copied;
      if (copied - lastReport >= 512 * 1024 || copied === job.totalBytes) {
        lastReport = copied;
        this.changed();
      }
    };
    const isCancelled = () => this.cancelled.has(job.id);
    try {
      const { size, chunks, close } = await this.openSource(item);
      if (size > 0) job.totalBytes = size;
      try {
        const savedAs = await this.write(job.target, item.name, chunks, progress, isCancelled);
        if (savedAs !== item.name) job.savedAs = savedAs;
      } finally {
        await close();
      }
      job.copiedBytes = job.totalBytes;
      job.state = "done";
      this.changed();
      this.deps.onCopied?.({ ...job });
    } catch (err) {
      job.state = "failed";
      job.error = (err as Error).message;
      this.changed();
    } finally {
      this.items.delete(job.id);
    }
  }

  private async openSource(item: MediaItem): Promise<{ size: number; chunks: AsyncIterable<Buffer>; close: () => Promise<void> }> {
    if (item.source.type === "local") {
      const stat = await fs.stat(item.path);
      const stream = createReadStream(item.path, { highWaterMark: CHUNK });
      return { size: stat.size, chunks: stream as AsyncIterable<Buffer>, close: async () => void stream.destroy() };
    }
    const shareId = item.source.shareId;
    const share = this.share(shareId);
    const handle = await openRawFile(share, relPathFromMediaPath(share, item.path));
    async function* read(): AsyncGenerator<Buffer> {
      let pos = 0;
      while (pos < handle.size) {
        const data = await readRawRange(handle, pos, Math.min(CHUNK, handle.size - pos));
        if (data.length === 0) break;
        pos += data.length;
        yield data;
      }
    }
    return { size: handle.size, chunks: read(), close: () => closeRawFile(handle) };
  }

  private async write(
    target: CopyTarget,
    requested: string,
    chunks: AsyncIterable<Buffer>,
    progress: (copied: number) => void,
    isCancelled: () => boolean
  ): Promise<string> {
    const name = cleanName(requested);
    if (target.kind === "share") {
      const share = this.share(target.shareId);
      const client = connectShare(share);
      try {
        const dir = sharePath(share, cleanRelPath(target.relPath));
        if (dir) await smbMkdirp(client, dir);
        const file = await smbCreateUnique(client, dir, name, (n) => numberedName(name, n));
        let ok = false;
        try {
          await smbWriteAll(client, file.fileId, chunks, progress, isCancelled);
          ok = true;
        } finally {
          await smbCloseFile(client, file.fileId).catch(() => undefined);
          if (!ok) await smbDelete(client, file.path).catch(() => undefined);
        }
        return file.name;
      } finally {
        client.close();
      }
    }

    const dir = this.localDir(target);
    await fs.mkdir(dir, { recursive: true });
    for (let n = 1; n < 1000; n++) {
      const candidate = numberedName(name, n);
      const dest = path.join(dir, candidate);
      let out: ReturnType<typeof createWriteStream> | undefined;
      try {
        out = createWriteStream(dest, { flags: "wx" }); // fails if it exists: never overwrite
        await new Promise<void>((resolve, reject) => {
          out!.once("open", () => resolve());
          out!.once("error", reject);
        });
      } catch (err) {
        if ((err as NodeJS.ErrnoException).code === "EEXIST") continue;
        throw err;
      }
      let copied = 0;
      try {
        for await (const chunk of chunks) {
          if (isCancelled()) throw new Error("Cancelled.");
          if (!out.write(chunk)) await new Promise<void>((resolve) => out!.once("drain", () => resolve()));
          copied += chunk.length;
          progress(copied);
        }
        await new Promise<void>((resolve, reject) => out!.end((err?: Error | null) => (err ? reject(err) : resolve())));
      } catch (err) {
        out.destroy();
        await fs.rm(dest, { force: true });
        throw err;
      }
      return candidate;
    }
    throw new Error(`Too many files called "${name}" already.`);
  }

  private localDir(target: CopyTarget): string {
    if (target.kind === "path") return target.path;
    if (target.kind !== "folder") throw new Error("not a local target");
    const folder = this.deps.settings().localFolders.find((f) => f.id === target.folderId);
    if (!folder) throw new Error("That folder is no longer configured.");
    const root = path.resolve(folder.path);
    const dir = path.resolve(root, cleanRelPath(target.relPath));
    if (dir !== root && !dir.startsWith(root + path.sep)) throw new Error("That folder is outside the library folder.");
    return dir;
  }

  private share(id: string): NetworkShare {
    const share = this.deps.settings().networkShares.find((s) => s.id === id);
    if (!share) throw new Error("That network share is no longer configured.");
    return share;
  }

  private changed(): void {
    this.deps.onChange?.(this.list());
  }
}
