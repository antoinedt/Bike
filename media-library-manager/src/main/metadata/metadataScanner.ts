import { promises as fs } from "node:fs";
import { pathToFileURL } from "node:url";
import type { MediaItem, MediaMetadata, NetworkShare } from "../../shared/types.js";
import { fetchMetadataFromItunes, guessTitle } from "../artwork/artworkFetcher.js";
import { relPathFromMediaPath, writeNetworkSidecar } from "../network/smbClient.js";
import { ensureStreamServer, registerStream, unregisterStream } from "../network/streamServer.js";
import { probeDurationSeconds } from "./durationProbe.js";

const CONCURRENCY = 2;

/**
 * Fills in title/year/length (and artwork, where missing) for every video/audio item that
 * doesn't already have them, and writes a ".metadata.json" sidecar next to each media file so
 * the metadata travels with the file rather than living only in this app's local database.
 * Bounded concurrency, lower than the artwork fetcher's — each item here opens a real (hidden)
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
  const hasAnything = metadata.title || metadata.year || metadata.durationSeconds !== undefined || metadata.artworkUrl;
  return hasAnything ? metadata : null;
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
