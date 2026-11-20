import { promises as fs } from "node:fs";
import { pathToFileURL } from "node:url";
import type { MediaItem, MediaMetadata, NetworkShare, ScanLogEntry, ScanProgressEvent } from "../../shared/types.js";
import { fetchArtworkFromGoogle, fetchMetadataFromItunes, guessTitle } from "../artwork/artworkFetcher.js";
import { readNetworkSidecar, relPathFromMediaPath, writeNetworkSidecar } from "../network/smbClient.js";
import { ensureStreamServer, registerStream, unregisterStream } from "../network/streamServer.js";
import { probeDurationSeconds } from "./durationProbe.js";
import { extractVideoThumbnail } from "./thumbnailExtractor.js";

const CONCURRENCY = 2;

/**
 * Fills in title/year/length/cover art for every video/audio item that hasn't been checked yet
 * (item.infoFetched), local or network, limited to whatever `items` the caller passes in — the
 * caller decides the scope (e.g. just the folder the user is currently browsing). For each
 * candidate, first checks for a ".metadata.json" sidecar next to the file — written there by a
 * previous run on this device or any other device that scanned the same share — and uses it
 * directly if present, skipping the iTunes lookup and duration probe entirely. Only items with no
 * sidecar and no match do a real fetch. Every attempt — success or total failure — is written back
 * as a sidecar (local fs, or uploaded to the network share) marked infoFetched so it's there next
 * time, on any device, and so a failed attempt doesn't get silently retried forever or look
 * identical to "never checked": the UI uses infoFetched to show "no info found — add it yourself"
 * rather than nothing at all. Pass `force: true` to re-fetch every item in scope regardless of
 * infoFetched or any cached sidecar, overriding whatever was found before — the user's manual
 * override for a stuck "no match" result. Bounded concurrency, lower than typical — each
 * un-cached item opens a real (hidden) browser window to read its duration, which is heavier than
 * a plain HTTP request.
 *
 * `onProgress`, if given, is called with a live event per item (started, then done/cached/error)
 * as the scan runs — this is what drives the progress dialog's log, including the per-step
 * reason an item came up with nothing (no match on iTunes, no match on Google either, not a
 * video so no frame to fall back to, or an outright error like a network failure). `signal`, if
 * given, stops the scan from picking up any further items once aborted (the user's Cancel
 * button) and is passed into the underlying HTTP requests so an in-flight one aborts quickly too
 * — whatever was already found for earlier items is kept, not thrown away.
 */
export async function scanMetadataForLibrary(
  items: MediaItem[],
  networkShares: NetworkShare[],
  force = false,
  options?: { signal?: AbortSignal; onProgress?: (event: ScanProgressEvent) => void }
): Promise<Map<string, MediaMetadata>> {
  const { signal, onProgress } = options ?? {};
  const candidates = items.filter(
    (item) => (item.kind === "video" || item.kind === "audio") && (force || !item.infoFetched)
  );
  const found = new Map<string, MediaMetadata>();

  let cursor = 0;
  async function worker(): Promise<void> {
    while (cursor < candidates.length && !signal?.aborted) {
      const item = candidates[cursor++];
      onProgress?.({ itemId: item.id, itemName: item.name, status: "started" });
      try {
        const cached = await readSidecar(item, networkShares).catch(() => null);
        // A sidecar already marked infoFetched is a fully-resolved result from a previous attempt
        // (on this device or another) — use it as-is, success or not, unless the caller asked to
        // force a fresh fetch anyway. One with only tags (e.g. the user tagged this item before
        // ever running a fetch on it) isn't resolved: fall through to a real fetch below, carrying
        // the existing tags forward so they aren't lost.
        if (!force && cached?.infoFetched) {
          found.set(item.id, cached);
          onProgress?.({ itemId: item.id, itemName: item.name, status: "cached" });
          continue;
        }
        const log: ScanLogEntry[] = [];
        const metadata = await buildMetadata(item, networkShares, signal, (entry) => log.push(entry));
        if (cached?.tags?.length) metadata.tags = cached.tags;
        found.set(item.id, metadata);
        await writeSidecar(item, metadata, networkShares).catch(() => {
          // Best-effort: a read-only share or a transient SMB error shouldn't lose the metadata
          // we already found — it's still applied to the app's own database below.
        });
        onProgress?.({ itemId: item.id, itemName: item.name, status: "done", log });
      } catch (err) {
        onProgress?.({ itemId: item.id, itemName: item.name, status: "error", error: (err as Error).message });
      }
    }
  }

  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, candidates.length) }, worker));
  return found;
}

async function buildMetadata(
  item: MediaItem,
  networkShares: NetworkShare[],
  signal: AbortSignal | undefined,
  log: (entry: ScanLogEntry) => void
): Promise<MediaMetadata> {
  const guessed = guessTitle(item.name);

  let itunes: Awaited<ReturnType<typeof fetchMetadataFromItunes>> = null;
  try {
    itunes = await fetchMetadataFromItunes(item, signal);
    log({ step: "iTunes", result: itunes ? "found" : "not-found" });
  } catch (err) {
    log({ step: "iTunes", result: "error", detail: (err as Error).message });
  }

  let durationSeconds: number | null = null;
  try {
    durationSeconds = await probeDuration(item, networkShares);
    log({ step: "Duration", result: durationSeconds != null ? "found" : "not-found" });
  } catch (err) {
    log({ step: "Duration", result: "error", detail: (err as Error).message });
  }

  const metadata: MediaMetadata = {
    title: itunes?.title || guessed.title,
    year: itunes?.year || guessed.year,
    durationSeconds: durationSeconds ?? undefined,
    artworkUrl: itunes?.artworkUrl,
    infoFetched: true
  };

  // iTunes only knows its own commercial catalog — plenty of a legally-obtained library (less
  // common movies, most songs, home videos) will never match there. Fall back to a plain Google
  // search for whatever we're missing before giving up on it.
  if (!metadata.artworkUrl || !metadata.year) {
    const query = metadata.year ? `${metadata.title} ${metadata.year}` : metadata.title;
    try {
      const google = await fetchArtworkFromGoogle(query ?? guessed.title, signal);
      log({ step: "Google search", result: google.artworkUrl || google.year ? "found" : "not-found" });
      if (!metadata.artworkUrl) metadata.artworkUrl = google.artworkUrl;
      if (!metadata.year) metadata.year = google.year;
    } catch (err) {
      log({ step: "Google search", result: "error", detail: (err as Error).message });
    }
  }

  // Still nothing? For video, a real frame pulled from the file itself works for literally
  // anything with a video track, no external lookup required — the last resort.
  if (!metadata.artworkUrl && item.kind === "video") {
    try {
      const thumb = await extractThumbnail(item, networkShares);
      log({ step: "Video frame", result: thumb ? "found" : "not-found" });
      metadata.artworkUrl = thumb ?? undefined;
    } catch (err) {
      log({ step: "Video frame", result: "error", detail: (err as Error).message });
    }
  }

  return metadata;
}

async function extractThumbnail(item: MediaItem, networkShares: NetworkShare[]): Promise<string | null> {
  const source = item.source;
  if (source.type === "local") {
    return extractVideoThumbnail(pathToFileURL(item.path).href);
  }
  const share = networkShares.find((s) => s.id === source.shareId);
  if (!share) return null;
  const relPath = relPathFromMediaPath(share, item.path);
  const port = await ensureStreamServer();
  const token = registerStream(share, relPath, item.extension);
  try {
    return await extractVideoThumbnail(`http://127.0.0.1:${port}/stream/${token}`);
  } finally {
    unregisterStream(token);
  }
}

async function probeDuration(item: MediaItem, networkShares: NetworkShare[]): Promise<number | null> {
  const source = item.source;
  if (source.type === "local") {
    return probeDurationSeconds(pathToFileURL(item.path).href);
  }
  const share = networkShares.find((s) => s.id === source.shareId);
  if (!share) return null;
  const relPath = relPathFromMediaPath(share, item.path);
  const port = await ensureStreamServer();
  const token = registerStream(share, relPath, item.extension);
  try {
    return await probeDurationSeconds(`http://127.0.0.1:${port}/stream/${token}`);
  } finally {
    unregisterStream(token);
  }
}

export async function readSidecar(item: MediaItem, networkShares: NetworkShare[]): Promise<MediaMetadata | null> {
  const source = item.source;
  if (source.type === "local") {
    try {
      const raw = await fs.readFile(`${item.path}.metadata.json`, "utf8");
      return JSON.parse(raw) as MediaMetadata;
    } catch {
      return null;
    }
  }
  const share = networkShares.find((s) => s.id === source.shareId);
  if (!share) return null;
  const relPath = relPathFromMediaPath(share, item.path);
  return readNetworkSidecar(share, relPath);
}

export async function writeSidecar(item: MediaItem, metadata: MediaMetadata, networkShares: NetworkShare[]): Promise<void> {
  const source = item.source;
  if (source.type === "local") {
    await fs.writeFile(`${item.path}.metadata.json`, JSON.stringify(metadata, null, 2), "utf8");
    return;
  }
  const share = networkShares.find((s) => s.id === source.shareId);
  if (!share) return;
  const relPath = relPathFromMediaPath(share, item.path);
  await writeNetworkSidecar(share, relPath, metadata);
}
