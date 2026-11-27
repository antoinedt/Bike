import { promises as fs } from "node:fs";
import path from "node:path";
import { classifyExtension } from "../../shared/types.js";
import type { LocalFolder, MediaItem } from "../../shared/types.js";
import { runPool } from "./pool.js";

const SKIP_DIR_NAMES = new Set([".git", "node_modules", "$RECYCLE.BIN", "System Volume Information"]);
const MAX_DEPTH = 12;
/** Folders read at the same time within one library folder. */
const CONCURRENCY = 6;

/** What a finished scan of one folder or share found. */
export interface SourceScanResult {
  /** False when the folder/share itself couldn't be read (unplugged drive, offline share): the
   *  caller must then keep what the library already has instead of treating everything as gone. */
  complete: boolean;
  seenIds: Set<string>;
}

/**
 * Scans a local folder for media files, reading several subfolders at once and handing each
 * folder's media to `onBatch` as soon as it's read, so the library fills in while the scan runs.
 * Symlinks are not followed to avoid cycles.
 */
export async function scanLocalFolder(folder: LocalFolder, onBatch: (items: MediaItem[]) => void = () => undefined): Promise<SourceScanResult> {
  const seenIds = new Set<string>();
  let rootReadable = true;
  await runPool([{ dir: folder.path, depth: 0 }], CONCURRENCY, async ({ dir, depth }, push) => {
    let entries: import("node:fs").Dirent[];
    try {
      entries = await fs.readdir(dir, { withFileTypes: true });
    } catch {
      if (depth === 0) rootReadable = false;
      return;
    }
    const files: { fullPath: string; name: string; ext: string; kind: MediaItem["kind"] }[] = [];
    for (const entry of entries) {
      if (entry.isSymbolicLink()) continue;
      const fullPath = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (SKIP_DIR_NAMES.has(entry.name) || entry.name.startsWith(".")) continue;
        if (depth + 1 <= MAX_DEPTH) push({ dir: fullPath, depth: depth + 1 });
      } else if (entry.isFile()) {
        const ext = path.extname(entry.name).slice(1);
        const kind = classifyExtension(ext);
        if (kind !== "other") files.push({ fullPath, name: entry.name, ext, kind });
      }
    }
    const stats = await Promise.all(files.map((f) => fs.stat(f.fullPath).catch(() => null)));
    const batch: MediaItem[] = [];
    files.forEach((f, i) => {
      const stat = stats[i];
      if (!stat) return;
      seenIds.add(f.fullPath);
      batch.push({
        // Stable (not random) so a rescan's items line up by id with what was there before —
        // db.ts relies on that to carry forward fetched artwork/info across rescans.
        id: f.fullPath,
        name: f.name,
        path: f.fullPath,
        kind: f.kind,
        extension: f.ext.toLowerCase(),
        sizeBytes: stat.size,
        modifiedAt: stat.mtimeMs,
        source: { type: "local", folderId: folder.id }
      });
    });
    if (batch.length > 0) onBatch(batch);
  });
  return { complete: rootReadable, seenIds };
}
