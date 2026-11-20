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
  trackName?: string;
  collectionName?: string;
  releaseDate?: string;
}

interface ITunesResponse {
  results: ITunesResult[];
}

function upsizeArtwork(url: string): string {
  return url.replace(/\d+x\d+bb(\.\w+)$/, "600x600bb$1");
}

async function searchItunes(term: string, entity: string, signal?: AbortSignal): Promise<ITunesResult | null> {
  const url = `https://itunes.apple.com/search?term=${encodeURIComponent(term)}&entity=${entity}&limit=1`;
  const res = await fetch(url, { headers: { "User-Agent": USER_AGENT }, signal });
  if (!res.ok) return null;
  const data = (await res.json()) as ITunesResponse;
  return data.results[0] ?? null;
}

export interface ItunesMetadata {
  title?: string;
  year?: string;
  artworkUrl?: string;
}

// A real browser UA, not this app's own — Google blocks/CAPTCHAs a plainly automated UA far more
// aggressively than one that looks like an ordinary browser doing the same search a person would
// do by hand.
const GOOGLE_USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

export interface GoogleSearchMetadata {
  artworkUrl?: string;
  year?: string;
}

/**
 * Falls back to a plain Google search for the guessed title (iTunes only knows its own
 * commercial catalog, which plenty of a legally-obtained library — less common movies, most
 * songs, anything iTunes just doesn't carry — will never match): takes the first image result as
 * artwork, and a parenthesized "(19xx)"/"(20xx)" on the page as the release year, if either is
 * present. This scrapes Google's search-results HTML for an "ou" ("original url") field Google
 * embeds server-side for each image result — a long-standing, widely-used but unofficial trick
 * (no API key, no official support) that will simply stop matching, not throw, if Google changes
 * its markup; every caller treats a fully-empty result the same as "nothing found".
 */
export async function fetchArtworkFromGoogle(query: string, signal?: AbortSignal): Promise<GoogleSearchMetadata> {
  const url = `https://www.google.com/search?q=${encodeURIComponent(query)}&hl=en`;
  const res = await fetch(url, {
    headers: { "User-Agent": GOOGLE_USER_AGENT, "Accept-Language": "en-US,en;q=0.9" },
    signal
  });
  if (!res.ok) return {};
  const html = await res.text();

  const imageMatch = /"ou":"(https?:\\?\/\\?\/[^"]+?)"/.exec(html);
  const artworkUrl = imageMatch ? imageMatch[1].replace(/\\\//g, "/") : undefined;

  const yearMatch = /\((19|20)\d{2}\)/.exec(html);
  const year = yearMatch ? yearMatch[0].slice(1, -1) : undefined;

  return { artworkUrl, year };
}

/**
 * Looks up title/release-year/artwork for one item in a single iTunes Search API call — used by
 * the metadata scanner (Settings -> Scan metadata), which also covers duration and sidecar
 * persistence; this is just the iTunes half of that.
 */
export async function fetchMetadataFromItunes(item: MediaItem, signal?: AbortSignal): Promise<ItunesMetadata | null> {
  const { title, year } = guessTitle(item.name);
  if (!title) return null;
  const term = year ? `${title} ${year}` : title;

  let result: ITunesResult | null = null;
  if (item.kind === "video") {
    result = await searchItunes(term, "movie", signal);
  } else if (item.kind === "audio") {
    result = await searchItunes(title, "song", signal);
  }
  if (!result) return null;

  return {
    title: result.trackName || result.collectionName,
    year: result.releaseDate ? result.releaseDate.slice(0, 4) : undefined,
    artworkUrl: result.artworkUrl100 ? upsizeArtwork(result.artworkUrl100) : undefined
  };
}
