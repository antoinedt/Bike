import SMB2 from "smb2";
import { randomUUID } from "node:crypto";
import { classifyExtension } from "../../shared/types.js";
import type { MediaItem, NetworkShare } from "../../shared/types.js";

const MAX_DEPTH = 10;

function uncPath(share: NetworkShare): string {
  return `\\\\${share.host}\\${share.share}`;
}

function createClient(share: NetworkShare): SMB2 {
  return new SMB2({
    share: uncPath(share),
    domain: share.domain || "WORKGROUP",
    username: share.username,
    password: share.password
  });
}

function joinSmbPath(...parts: string[]): string {
  return parts.filter(Boolean).join("\\");
}

/** Confirms the share is reachable and the credentials work by listing its root. */
export async function testShareConnection(share: NetworkShare): Promise<{ ok: boolean; error?: string }> {
  const client = createClient(share);
  try {
    await readdir(client, share.subPath);
    return { ok: true };
  } catch (err) {
    return { ok: false, error: (err as Error).message };
  } finally {
    client.close();
  }
}

function readdir(client: SMB2, path: string): Promise<string[]> {
  return new Promise((resolve, reject) => {
    client.readdir(path, (err, files) => (err ? reject(err) : resolve(files)));
  });
}

/**
 * The bare-bones smb2 client has no stat/readdir-with-types call, so directory vs. file is
 * determined by attempting a readdir: it succeeds for directories and fails for files.
 */
async function isDirectory(client: SMB2, path: string): Promise<boolean> {
  try {
    await readdir(client, path);
    return true;
  } catch {
    return false;
  }
}

/**
 * Recursively scans an SMB share for media files. File size/modified time are not available
 * through this lightweight SMB2 client, so those fields are left at 0 — see README.
 */
export async function scanNetworkShare(share: NetworkShare): Promise<MediaItem[]> {
  const client = createClient(share);
  const items: MediaItem[] = [];
  try {
    await walk(share.subPath, 0);
  } finally {
    client.close();
  }
  return items;

  async function walk(dir: string, depth: number): Promise<void> {
    if (depth > MAX_DEPTH) return;
    let entries: string[];
    try {
      entries = await readdir(client, dir);
    } catch {
      return;
    }
    for (const name of entries) {
      const entryPath = joinSmbPath(dir, name);
      if (await isDirectory(client, entryPath)) {
        await walk(entryPath, depth + 1);
        continue;
      }
      const ext = name.includes(".") ? name.split(".").pop()! : "";
      const kind = classifyExtension(ext);
      if (kind === "other") continue;
      items.push({
        id: randomUUID(),
        name,
        path: `smb://${share.host}/${share.share}/${entryPath.replace(/\\/g, "/")}`,
        kind,
        extension: ext.toLowerCase(),
        sizeBytes: 0,
        modifiedAt: 0,
        source: { type: "network", shareId: share.id }
      });
    }
  }
}

/** Recovers the share-relative path (forward-slashed) that a scanned item's `smb://` path encodes. */
export function relPathFromMediaPath(share: NetworkShare, mediaPath: string): string {
  const prefix = `smb://${share.host}/${share.share}/`;
  return mediaPath.startsWith(prefix) ? mediaPath.slice(prefix.length) : mediaPath;
}
