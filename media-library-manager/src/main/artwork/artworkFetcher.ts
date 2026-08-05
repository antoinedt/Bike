import type { MediaItem } from "../../shared/types.js";

const USER_AGENT = "Mozilla/5.0 (Media Library Manager; +desktop app) media-library-manager/0.1";

/** Release-group / quality tags commonly found in scene-style filenames, used to cut the title short. */
const NOISE_MARKERS =
  /\b(1080p|2160p|720p|480p|4k|uhd|hdr|bluray|blu-ray|bdrip|brrip|webrip|web-dl|webdl|hdtv|dvdrip|remux|repack|proper|x264|x265|h264|h265|hevc|aac|dts|ac3|5\.1)\b/i;

/**
 * Guesses a clean search title from a scene-style filename, e.g.
 * "The.Movie.Name.2019.1080p.BluRay.x264-GROUP.mkv" -> { title: "The Movie Name", year: "2019" }.
 * Best-effort — feeds a search query, not a definitive title.
 */
export function guessTitle(filename: string): { title: string; year?: string } {
  const withoutExt = filename.replace(/\.[^.]+$/, "");
  let cleaned = withoutExt.replace(/[._]+/g, " ").replace(/\s+/g, " ").trim();

  const noiseMatch = NOISE_MARKERS.exec(cleaned);
  if (noiseMatch) cleaned = cleaned.slice(0, noiseMatch.index).trim();

  const yearMatch = /\b(19|20)\d{2}\b/.exec(cleaned);
  let year: string | undefined;
  if (yearMatch) {
    year = yearMatch[0];
    cleaned = cleaned.slice(0, yearMatch.index).trim();
  }

  cleaned = cleaned.replace(/[-([{].*$/, "").trim();
  return { title: cleaned || withoutExt, year };
}

interface ITunesResult {
  artworkUrl100?: string;
}

interface ITunesResponse {
  results: ITunesResult[];
}

function upsizeArtwork(url: string): string {
  return url.replace(/\d+x\d+bb(\.\w+)$/, "600x600bb$1");
}

async function lookup(term: string, entity: string): Promise<string | null> {
  const url = `https://itunes.apple.com/search?term=${encodeURIComponent(term)}&entity=${entity}&limit=1`;
  const res = await fetch(url, { headers: { "User-Agent": USER_AGENT } });
  if (!res.ok) return null;
  const data = (await res.json()) as ITunesResponse;
  const artwork = data.results[0]?.artworkUrl100;
  return artwork ? upsizeArtwork(artwork) : null;
}

/**
 * Looks up artwork for one item via the iTunes Search API (Apple's public, keyless catalog
 * search — no scraping, no API key to configure). Returns null if nothing suitable was found;
 * callers should treat that as "no artwork available" rather than retry indefinitely.
 */
export async function fetchArtworkUrl(item: MediaItem): Promise<string | null> {
  const { title, year } = guessTitle(item.name);
  if (!title) return null;
  const term = year ? `${title} ${year}` : title;

  if (item.kind === "video") {
    return lookup(term, "movie");
  }
  if (item.kind === "audio") {
    return lookup(title, "song");
  }
  return null;
}

const CONCURRENCY = 4;

/**
 * Fetches artwork for every item missing it, bounded concurrency to stay polite to the API.
 * Returns a Map of item id -> artwork URL for the items where something was found.
 */
export async function fetchArtworkForLibrary(items: MediaItem[]): Promise<Map<string, string>> {
  const candidates = items.filter((item) => !item.artworkUrl && (item.kind === "video" || item.kind === "audio"));
  const found = new Map<string, string>();

  let cursor = 0;
  async function worker(): Promise<void> {
    while (cursor < candidates.length) {
      const item = candidates[cursor++];
      const url = await fetchArtworkUrl(item).catch(() => null);
      if (url) found.set(item.id, url);
    }
  }

  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, candidates.length) }, worker));
  return found;
}
