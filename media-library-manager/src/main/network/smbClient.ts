import SMB2 from "smb2";
import { classifyExtension } from "../../shared/types.js";
import type { MediaItem, MediaMetadata, NetworkShare } from "../../shared/types.js";
import { runPool } from "../library/pool.js";
import { portOf, uncPath } from "./smbRawClient.js";

const MAX_DEPTH = 10;

function createClient(share: NetworkShare): SMB2 {
  return new SMB2({
    share: uncPath(share),
    domain: share.domain || "WORKGROUP",
    username: share.username,
    password: share.password,
    port: portOf(share)
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

/** Folders listed at the same time on one share (one SMB session, requests multiplexed). */
const SMB_CONCURRENCY = 4;

/** Sidecars and other files that are never folders — no need to probe them with a listing. */
const NOT_A_FOLDER = /\.(srt|ass|ssa|sub|idx|vtt|smi|nfo|txt|json|xml|db|ini|url|lnk|log|md5|sfv|sha1|sha256|par2|torrent|nzb|exe|dll|zip|rar|7z|tar|gz|pdf|docx?|html?|part|tmp|bak|cue|m3u8?|pls)$/i;

/**
 * Scans an SMB share for media files, listing several folders at once and handing each folder's
 * media to `onBatch` as soon as it's read, so the library fills in while the scan runs. Throws if
 * the share itself can't be reached, so callers keep what the library already has. File size /
 * modified time aren't available through this lightweight SMB2 client, so they're left at 0.
 */
export async function scanNetworkShare(
  share: NetworkShare,
  onBatch: (items: MediaItem[]) => void = () => undefined,
  excluded: ReadonlySet<string> = new Set()
): Promise<{ complete: boolean; seenIds: Set<string> }> {
  const client = createClient(share);
  const seenIds = new Set<string>();
  try {
    // The root listing goes first and alone: it opens the session (this client can't connect
    // from several requests at once) and tells us whether the share is reachable at all.
    const rootEntries = await readdir(client, share.subPath);
    type Task = { dir: string; depth: number; entries?: string[] };
    await runPool<Task>([{ dir: share.subPath, depth: 0, entries: rootEntries }], SMB_CONCURRENCY, async (task, push) => {
      const entries = task.entries ?? (await readdir(client, task.dir).catch(() => null));
      if (!entries) return;
      const batch: MediaItem[] = [];
      for (const name of entries) {
        const entryPath = joinSmbPath(task.dir, name);
        const ext = name.includes(".") ? name.split(".").pop()! : "";
        const kind = classifyExtension(ext);
        if (kind !== "other") {
          // A media file name. (Telling files from folders costs a listing over the network, so
          // only names that could be folders get that probe.)
          const mediaPath = `smb://${share.host}/${share.share}/${entryPath.replace(/\\/g, "/")}`;
          seenIds.add(mediaPath);
          batch.push({
            // Stable (not random) so a rescan's items line up by id with what was there before —
            // db.ts relies on that to carry forward fetched artwork/info across rescans.
            id: mediaPath,
            name,
            path: mediaPath,
            kind,
            extension: ext.toLowerCase(),
            sizeBytes: 0,
            modifiedAt: 0,
            source: { type: "network", shareId: share.id }
          });
          continue;
        }
        if (name.startsWith(".") || NOT_A_FOLDER.test(name) || task.depth + 1 > MAX_DEPTH) continue;
        if (excluded.has(`network:${share.id}:${entryPath.replace(/\\/g, "/").replace(/^\/+/, "")}`)) continue;
        // Might be a folder: listing it works for folders and fails for files, and the listing is
        // reused for the folder's own visit.
        const sub = await readdir(client, entryPath).catch(() => null);
        if (sub) push({ dir: entryPath, depth: task.depth + 1, entries: sub });
      }
      if (batch.length > 0) onBatch(batch);
    });
  } finally {
    client.close();
  }
  return { complete: true, seenIds };
}

/** Recovers the share-relative path (forward-slashed) that a scanned item's `smb://` path encodes. */
export function relPathFromMediaPath(share: NetworkShare, mediaPath: string): string {
  const prefix = `smb://${share.host}/${share.share}/`;
  return mediaPath.startsWith(prefix) ? mediaPath.slice(prefix.length) : mediaPath;
}

function writeFile(client: SMB2, path: string, data: string): Promise<void> {
  return new Promise((resolve, reject) => {
    client.writeFile(path, data, (err) => (err ? reject(err) : resolve()));
  });
}

function readFile(client: SMB2, path: string): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    client.readFile(path, (err, data) => (err ? reject(err) : resolve(data)));
  });
}

function sidecarSmbPath(relPath: string): string {
  return `${relPath}.metadata.json`.replace(/\//g, "\\");
}

/**
 * Writes a metadata sidecar JSON file next to a media file on the share (same directory, same
 * base name, ".metadata.json" appended) so the metadata travels with the file rather than living
 * only in this app's local database — any other device browsing this same share (or this device,
 * after wiping its own local database) picks it up via readNetworkSidecar instead of re-fetching.
 */
export async function writeNetworkSidecar(
  share: NetworkShare,
  relPath: string,
  metadata: MediaMetadata
): Promise<void> {
  const client = createClient(share);
  try {
    await writeFile(client, sidecarSmbPath(relPath), JSON.stringify(metadata, null, 2));
  } finally {
    client.close();
  }
}

/** Reads a previously-written metadata sidecar from the share, if one exists; null otherwise. */
export async function readNetworkSidecar(share: NetworkShare, relPath: string): Promise<MediaMetadata | null> {
  const client = createClient(share);
  try {
    const buf = await readFile(client, sidecarSmbPath(relPath));
    return JSON.parse(buf.toString("utf8")) as MediaMetadata;
  } catch {
    return null;
  } finally {
    client.close();
  }
}
