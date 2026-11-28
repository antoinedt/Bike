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
/** The last 1900..next-year number in [text] ("Blade Runner 2099" is a title, not a year). */
function plausibleYear(text: string): RegExpExecArray | null {
  const max = new Date().getFullYear() + 1;
  let last: RegExpExecArray | null = null;
  const re = /\b(19|20)\d{2}\b/g;
  for (let m = re.exec(text); m; m = re.exec(text)) if (Number(m[0]) >= 1900 && Number(m[0]) <= max) last = m;
  return last;
}

/**
 * [episode] ("S01E01") is set when the file is a TV episode; [title] is then the show's name, so
 * lookups search for the show instead of a movie named after the whole file.
 */
export function guessTitle(filename: string): { title: string; year?: string; episode?: string } {
  const withoutExt = filename.replace(/\.[^.]+$/, "");
  let cleaned = withoutExt.replace(/[._]+/g, " ").replace(/\s+/g, " ").trim();

  const ep = /\b(?:S(\d{1,2})[\s._-]?E(\d{1,3})|(\d{1,2})x(\d{2,3}))\b/i.exec(cleaned);
  if (ep) {
    let show = cleaned.slice(0, ep.index).replace(/[\s\-([]+$/, "").trim();
    const season = Number(ep[1] ?? ep[3]);
    const episodeNo = Number(ep[2] ?? ep[4]);
    let year: string | undefined;
    const y = plausibleYear(show);
    if (y && y.index > 0 && y.index + 4 === show.length) {
      year = y[0];
      show = show.slice(0, y.index).replace(/[\s([]+$/, "").trim();
    }
    if (show) {
      return { title: show, year, episode: `S${String(season).padStart(2, "0")}E${String(episodeNo).padStart(2, "0")}` };
    }
  }

  const noiseMatch = NOISE_MARKERS.exec(cleaned);
  if (noiseMatch) cleaned = cleaned.slice(0, noiseMatch.index).trim();

  const yearMatch = plausibleYear(cleaned);
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

/** TMDb TV search only — for an episode, whose show is known to be a series. */
export async function fetchTvFromTmdb(apiKey: string, show: string, year: string | undefined, signal?: AbortSignal): Promise<TmdbMetadata> {
  if (!apiKey) return {};
  const tv = await searchTmdb<TmdbTvResult>("tv", apiKey, show, year, signal);
  return tv ? { artworkUrl: tv.poster_path ? `${TMDB_IMAGE_BASE}${tv.poster_path}` : undefined, year: tv.first_air_date?.slice(0, 4) } : {};
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
  if (guessed.episode && item.kind === "video") {
    // A TV episode: its show's season artwork; titled "Show S01E01" so episodes stay apart.
    const show = await searchItunes(guessed.title, "tvSeason", signal);
    if (!show) return null;
    return {
      title: `${guessed.title} ${guessed.episode}`,
      year: guessed.year ?? (show.releaseDate ? show.releaseDate.slice(0, 4) : undefined),
      artworkUrl: show.artworkUrl100 ? upsizeArtwork(show.artworkUrl100) : undefined
    };
  }
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
