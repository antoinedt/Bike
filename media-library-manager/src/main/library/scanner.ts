import { promises as fs } from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { classifyExtension } from "../../shared/types.js";
import type { LocalFolder, MediaItem } from "../../shared/types.js";

const SKIP_DIR_NAMES = new Set([".git", "node_modules", "$RECYCLE.BIN", "System Volume Information"]);
const MAX_DEPTH = 12;

/** Recursively scans a local folder for media files. Symlinks are not followed to avoid cycles. */
export async function scanLocalFolder(folder: LocalFolder): Promise<MediaItem[]> {
  const items: MediaItem[] = [];
  await walk(folder.path, 0);
  return items;

  async function walk(dir: string, depth: number): Promise<void> {
    if (depth > MAX_DEPTH) return;
    let entries: import("node:fs").Dirent[];
    try {
      entries = await fs.readdir(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      if (entry.isSymbolicLink()) continue;
      const fullPath = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (SKIP_DIR_NAMES.has(entry.name) || entry.name.startsWith(".")) continue;
        await walk(fullPath, depth + 1);
      } else if (entry.isFile()) {
        const ext = path.extname(entry.name).slice(1);
        const kind = classifyExtension(ext);
        if (kind === "other") continue;
        let stat: import("node:fs").Stats;
        try {
          stat = await fs.stat(fullPath);
        } catch {
          continue;
        }
        items.push({
          id: randomUUID(),
          name: entry.name,
          path: fullPath,
          kind,
          extension: ext.toLowerCase(),
          sizeBytes: stat.size,
          modifiedAt: stat.mtimeMs,
          source: { type: "local", folderId: folder.id }
        });
      }
    }
  }
}

export async function scanLocalFolders(folders: LocalFolder[]): Promise<MediaItem[]> {
  const results = await Promise.all(folders.map(scanLocalFolder));
  return results.flat();
}
