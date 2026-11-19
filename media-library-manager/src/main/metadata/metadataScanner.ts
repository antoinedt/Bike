import { promises as fs } from "node:fs";
import { pathToFileURL } from "node:url";
import type { MediaItem, MediaMetadata, NetworkShare } from "../../shared/types.js";
import { fetchMetadataFromItunes, guessTitle } from "../artwork/artworkFetcher.js";
import { readNetworkSidecar, relPathFromMediaPath, writeNetworkSidecar } from "../network/smbClient.js";
import { ensureStreamServer, registerStream, unregisterStream } from "../network/streamServer.js";
import { probeDurationSeconds } from "./durationProbe.js";
import { extractVideoThumbnail } from "./thumbnailExtractor.js";

const CONCURRENCY = 2;

/**
 * Fills in title/year/length/cover art for every video/audio item that doesn't already have
 * them, local or network. For each candidate, first checks for a ".metadata.json" sidecar next
 * to the file — written there by a previous run on this device or any other device that scanned
 * the same share — and uses it directly if present, skipping the iTunes lookup and duration
 * probe entirely. Only items with no sidecar and no match do a real fetch, which is then written
 * back as a sidecar (local fs, or uploaded to the network share) so it's there next time, on any
 * device. Bounded concurrency, lower than typical — each un-cached item opens a real (hidden)
 * browser window to read its duration, which is heavier than a plain HTTP request.
 */
export async function scanMetadataForLibrary(
  items: MediaItem[],
  networkShares: NetworkShare[]
): Promise<Map<string, MediaMetadata>> {
  const candidates = items.filter(
    (item) => (item.kind === "video" || item.kind === "audio") && (!item.title || item.durationSeconds === undefined)
  );
  const found = new Map<string, MediaMetadata>();

  let cursor = 0;
  async function worker(): Promise<void> {
    while (cursor < candidates.length) {
      const item = candidates[cursor++];
      try {
        const cached = await readSidecar(item, networkShares).catch(() => null);
        if (cached) {
          found.set(item.id, cached);
          continue;
        }
        const metadata = await buildMetadata(item, networkShares);
        if (!metadata) continue;
        found.set(item.id, metadata);
        await writeSidecar(item, metadata, networkShares).catch(() => {
          // Best-effort: a read-only share or a transient SMB error shouldn't lose the metadata
          // we already found — it's still applied to the app's own database below.
        });
      } catch {
        // Best-effort per item; move on to the rest of the library.
      }
    }
  }

  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, candidates.length) }, worker));
  return found;
}

async function buildMetadata(item: MediaItem, networkShares: NetworkShare[]): Promise<MediaMetadata | null> {
  const guessed = guessTitle(item.name);
  const itunes = await fetchMetadataFromItunes(item).catch(() => null);
  const durationSeconds = await probeDuration(item, networkShares).catch(() => null);

  const metadata: MediaMetadata = {
    title: itunes?.title || guessed.title,
    year: itunes?.year || guessed.year,
    durationSeconds: durationSeconds ?? undefined,
    artworkUrl: itunes?.artworkUrl
  };

  // iTunes only knows commercial movies/music — most of a home media library (camera footage,
  // home videos) will never match there. Fall back to a real frame pulled from the video itself,
  // which works for literally anything with a video track, no external lookup required.
  if (!metadata.artworkUrl && item.kind === "video") {
    metadata.artworkUrl = (await extractThumbnail(item, networkShares).catch(() => null)) ?? undefined;
  }

  const hasAnything = metadata.title || metadata.year || metadata.durationSeconds !== undefined || metadata.artworkUrl;
  return hasAnything ? metadata : null;
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

async function readSidecar(item: MediaItem, networkShares: NetworkShare[]): Promise<MediaMetadata | null> {
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

async function writeSidecar(item: MediaItem, metadata: MediaMetadata, networkShares: NetworkShare[]): Promise<void> {
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
