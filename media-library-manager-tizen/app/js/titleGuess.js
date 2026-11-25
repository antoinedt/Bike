/**
 * Guesses a clean display title from a scene-style filename — a plain-JS port of the same
 * logic used by the desktop and Android apps (artworkFetcher.ts / ArtworkFetcher.kt), kept in
 * sync by hand since there's no shared package across the three platforms. Purely cosmetic
 * filename cleanup for display (this build does no online lookups),
 * e.g. "The.Movie.Name.2019.1080p.BluRay.x264-GROUP.mkv" -> { title: "The Movie Name", year: "2019" }.
 */
var NOISE_MARKERS =
  /\b(1080p|2160p|720p|480p|4k|uhd|hdr|bluray|blu-ray|bdrip|brrip|webrip|web-dl|webdl|hdtv|dvdrip|remux|repack|proper|x264|x265|h264|h265|hevc|aac|dts|ac3|5\.1)\b/i;
var YEAR_MARKER = /\b(19|20)\d{2}\b/;

function guessTitle(filename) {
  var withoutExt = filename.replace(/\.[^.]+$/, "");
  var cleaned = withoutExt.replace(/[._]+/g, " ").replace(/\s+/g, " ").trim();

  var noiseMatch = NOISE_MARKERS.exec(cleaned);
  if (noiseMatch) {
    cleaned = cleaned.slice(0, noiseMatch.index).trim();
  }

  var year;
  var yearMatch = YEAR_MARKER.exec(cleaned);
  if (yearMatch) {
    year = yearMatch[0];
    cleaned = cleaned.slice(0, yearMatch.index).trim();
  }

  cleaned = cleaned.replace(/[-([{].*$/, "").trim();
  return { title: cleaned || withoutExt, year: year };
}
