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

async function searchItunes(term: string, entity: string): Promise<ITunesResult | null> {
  const url = `https://itunes.apple.com/search?term=${encodeURIComponent(term)}&entity=${entity}&limit=1`;
  const res = await fetch(url, { headers: { "User-Agent": USER_AGENT } });
  if (!res.ok) return null;
  const data = (await res.json()) as ITunesResponse;
  return data.results[0] ?? null;
}

export interface ItunesMetadata {
  title?: string;
  year?: string;
  artworkUrl?: string;
}

/**
 * Looks up title/release-year/artwork for one item in a single iTunes Search API call — used by
 * the metadata scanner (Settings -> Scan metadata), which also covers duration and sidecar
 * persistence; this is just the iTunes half of that.
 */
export async function fetchMetadataFromItunes(item: MediaItem): Promise<ItunesMetadata | null> {
  const { title, year } = guessTitle(item.name);
  if (!title) return null;
  const term = year ? `${title} ${year}` : title;

  let result: ITunesResult | null = null;
  if (item.kind === "video") {
    result = await searchItunes(term, "movie");
  } else if (item.kind === "audio") {
    result = await searchItunes(title, "song");
  }
  if (!result) return null;

  return {
    title: result.trackName || result.collectionName,
    year: result.releaseDate ? result.releaseDate.slice(0, 4) : undefined,
    artworkUrl: result.artworkUrl100 ? upsizeArtwork(result.artworkUrl100) : undefined
  };
}
