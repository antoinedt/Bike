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

export interface TmdbMetadata {
  artworkUrl?: string;
  year?: string;
}

interface TmdbMovieResult {
  poster_path?: string | null;
  release_date?: string;
}

interface TmdbTvResult {
  poster_path?: string | null;
  first_air_date?: string;
}

interface TmdbSearchResponse<T> {
  results: T[];
}

const TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500";

async function searchTmdb<T>(
  kind: "movie" | "tv",
  apiKey: string,
  query: string,
  year: string | undefined,
  signal?: AbortSignal
): Promise<T | null> {
  const yearParam = year
    ? kind === "movie"
      ? `&year=${encodeURIComponent(year)}`
      : `&first_air_date_year=${encodeURIComponent(year)}`
    : "";
  const url = `https://api.themoviedb.org/3/search/${kind}?api_key=${encodeURIComponent(apiKey)}&query=${encodeURIComponent(query)}${yearParam}`;
  const res = await fetch(url, { signal });
  if (!res.ok) throw new Error(`TMDb ${kind} search failed: HTTP ${res.status}`);
  const data = (await res.json()) as TmdbSearchResponse<T>;
  return data.results[0] ?? null;
}

/**
 * Falls back to TMDb (The Movie Database) — a free, official, keyless-to-browse-but-key-to-query
 * API for movie/TV posters and release years, for whatever iTunes' commercial catalog has nothing
 * for (less common movies, most TV, home videos iTunes will never know). Requires a free API key
 * from themoviedb.org/settings/api, set in Settings; returns an empty result (not an error) when
 * no key is configured, so this fallback is simply skipped rather than failing every item.
 * Searches movies first, then TV (a scene-style filename doesn't say which it is).
 */
export async function fetchArtworkFromTmdb(
  apiKey: string,
  query: string,
  year: string | undefined,
  signal?: AbortSignal
): Promise<TmdbMetadata> {
  if (!apiKey) return {};
  const movie = await searchTmdb<TmdbMovieResult>("movie", apiKey, query, year, signal);
  if (movie) {
    return { artworkUrl: movie.poster_path ? `${TMDB_IMAGE_BASE}${movie.poster_path}` : undefined, year: movie.release_date?.slice(0, 4) };
  }
  const tv = await searchTmdb<TmdbTvResult>("tv", apiKey, query, year, signal);
  if (tv) {
    return { artworkUrl: tv.poster_path ? `${TMDB_IMAGE_BASE}${tv.poster_path}` : undefined, year: tv.first_air_date?.slice(0, 4) };
  }
  return {};
}

export interface AlbumArtMetadata {
  artworkUrl?: string;
}

interface MusicBrainzRelease {
  id: string;
}

interface MusicBrainzSearchResponse {
  releases: MusicBrainzRelease[];
}

// MusicBrainz's usage etiquette asks for an app name + contact/homepage in the User-Agent, not a
// browser-impersonating one — unlike TMDb or iTunes, it has no API key to identify the caller by.
const MUSICBRAINZ_USER_AGENT = "MediaLibraryManager/0.1 (+https://github.com/antoinedt/Bike)";

/**
 * Falls back to MusicBrainz (release search) + the Cover Art Archive (front-cover image) for
 * audio items iTunes has nothing for — both free, official, keyless APIs, no API key or scraping
 * involved. Confirms the Cover Art Archive actually has an image for the matched release before
 * handing back its URL, since plenty of MusicBrainz releases have no cover art uploaded.
 */
export async function fetchAlbumArtFromMusicBrainz(query: string, signal?: AbortSignal): Promise<AlbumArtMetadata> {
  const searchUrl = `https://musicbrainz.org/ws/2/release/?query=${encodeURIComponent(query)}&fmt=json&limit=1`;
  const res = await fetch(searchUrl, { headers: { "User-Agent": MUSICBRAINZ_USER_AGENT }, signal });
  if (!res.ok) throw new Error(`MusicBrainz search failed: HTTP ${res.status}`);
  const data = (await res.json()) as MusicBrainzSearchResponse;
  const release = data.releases[0];
  if (!release) return {};

  const artUrl = `https://coverartarchive.org/release/${release.id}/front`;
  const artRes = await fetch(artUrl, { method: "HEAD", headers: { "User-Agent": MUSICBRAINZ_USER_AGENT }, signal });
  if (!artRes.ok) return {};
  return { artworkUrl: artUrl };
}

/**
 * Looks up title/release-year/artwork for one item in a single iTunes Search API call — used by
 * the metadata scanner (Settings -> Scan metadata), which also covers duration and sidecar
 * persistence; this is just the iTunes half of that. `titleOverride`/`yearOverride`, if given
 * (the item's existing title/year — a manual edit or a previous fetch's result), are searched
 * with instead of a fresh filename guess: a corrected title is a better search term than the raw
 * filename, and re-guessing from the filename on every re-fetch is exactly how a manual
 * correction gets silently search-away-from, not just display-overwritten.
 */
export async function fetchMetadataFromItunes(
  item: MediaItem,
  signal?: AbortSignal,
  titleOverride?: string,
  yearOverride?: string
): Promise<ItunesMetadata | null> {
  const guessed = guessTitle(item.name);
  const title = titleOverride || guessed.title;
  const year = yearOverride || guessed.year;
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
