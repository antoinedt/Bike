import * as cheerio from "cheerio";
import type { SearchResultItem, SiteProfile } from "../../shared/types.js";
import { fetchRenderedHtml } from "./headlessFetch.js";

const USER_AGENT =
  "Mozilla/5.0 (Media Library Manager; +desktop app) media-library-manager/0.1";

/** Caps how many result rows get a detail-page follow-up fetch per search, so a results page
 * with dozens of rows can't turn into dozens of sequential/parallel browser round trips. */
const MAX_DETAIL_PAGE_LOOKUPS = 30;
/** How many detail-page fetches run at once — bounded so headless-browser lookups (each a real
 * hidden browser window) don't pile up unboundedly, but still run well ahead of doing them one
 * at a time. */
const DETAIL_PAGE_CONCURRENCY = 4;

function buildUrl(template: string, query: string): string {
  return template.replace("{query}", encodeURIComponent(query));
}

async function fetchPlainHtml(url: string): Promise<{ html: string; finalUrl: string }> {
  const res = await fetch(url, { headers: { "User-Agent": USER_AGENT } });
  if (!res.ok) {
    throw new Error(`Request to ${url} failed with status ${res.status}`);
  }
  return { html: await res.text(), finalUrl: res.url || url };
}

/**
 * Fetches a page either as a plain HTTP request (fast, works for server-rendered sites) or via
 * a hidden headless browser (slower, but sees JS-rendered content) depending on the profile's
 * useHeadlessBrowser flag. `readySelector` is only used in the headless path — it's what we
 * wait for the page to render before scraping.
 */
async function fetchHtml(
  url: string,
  useHeadlessBrowser: boolean,
  readySelector: string
): Promise<{ html: string; finalUrl: string }> {
  return useHeadlessBrowser ? fetchRenderedHtml(url, readySelector) : fetchPlainHtml(url);
}

function resolveUrl(maybeRelative: string, baseUrl: string): string {
  if (!maybeRelative) return maybeRelative;
  if (maybeRelative.startsWith("magnet:")) return maybeRelative;
  try {
    return new URL(maybeRelative, baseUrl).toString();
  } catch {
    return maybeRelative;
  }
}

interface Candidate {
  title: string;
  downloadUrl: string;
  size: string;
  seeders: string;
}

/**
 * Runs a search against a user-configured site profile and parses the results with the
 * profile's CSS selectors. Only fetches sites the user explicitly configured in Settings.
 */
export async function searchSite(profile: SiteProfile, query: string): Promise<SearchResultItem[]> {
  const searchUrl = buildUrl(profile.searchUrlTemplate, query);
  const { html, finalUrl } = await fetchHtml(searchUrl, profile.useHeadlessBrowser, profile.resultItemSelector);
  const $ = cheerio.load(html);

  const rows = $(profile.resultItemSelector).toArray();
  const candidates: Candidate[] = [];

  for (const row of rows) {
    const $row = $(row);
    const title = $row.find(profile.titleSelector).first().text().trim();
    const rawLink =
      $row.find(profile.linkSelector).first().attr("href") ??
      $row.find(profile.linkSelector).first().text().trim();
    if (!title || !rawLink) continue;

    const downloadUrl = resolveUrl(rawLink, finalUrl);
    const size = profile.sizeSelector ? $row.find(profile.sizeSelector).first().text().trim() : "";
    const seeders = profile.seedersSelector
      ? $row.find(profile.seedersSelector).first().text().trim()
      : "";
    candidates.push({ title, downloadUrl, size, seeders });
  }

  const resolved = await resolveDownloadUrls(candidates, profile);

  return resolved.map((c) => ({
    title: c.title,
    downloadUrl: c.downloadUrl,
    size: c.size,
    seeders: c.seeders,
    sourceProfileId: profile.id,
    sourceProfileLabel: profile.label
  }));
}

/**
 * Resolves each candidate's final download URL, following through to its detail page when
 * needed — with bounded concurrency, not one at a time, since each follow-up can itself be a
 * multi-second headless-browser fetch. Candidates whose detail page doesn't resolve are
 * dropped, same as before.
 */
async function resolveDownloadUrls(candidates: Candidate[], profile: SiteProfile): Promise<Candidate[]> {
  if (!profile.detailPageLinkSelector) return candidates;

  const limited = candidates.slice(0, MAX_DETAIL_PAGE_LOOKUPS);
  const resolved: (Candidate | null)[] = new Array(limited.length).fill(null);

  let cursor = 0;
  async function worker(): Promise<void> {
    while (cursor < limited.length) {
      const index = cursor++;
      const candidate = limited[index];
      if (candidate.downloadUrl.startsWith("magnet:")) {
        resolved[index] = candidate;
        continue;
      }
      try {
        const downloadUrl = await resolveFromDetailPage(
          candidate.downloadUrl,
          profile.detailPageLinkSelector,
          profile.useHeadlessBrowser
        );
        resolved[index] = { ...candidate, downloadUrl };
      } catch {
        // drop this one, matching the previous "continue" behavior
      }
    }
  }

  await Promise.all(Array.from({ length: Math.min(DETAIL_PAGE_CONCURRENCY, limited.length) }, worker));
  return resolved.filter((c): c is Candidate => c !== null);
}

async function resolveFromDetailPage(
  detailPageUrl: string,
  selector: string,
  useHeadlessBrowser: boolean
): Promise<string> {
  const { html, finalUrl } = await fetchHtml(detailPageUrl, useHeadlessBrowser, selector);
  const $ = cheerio.load(html);
  const href = $(selector).first().attr("href");
  if (!href) throw new Error(`No download link found on detail page via selector "${selector}"`);
  return resolveUrl(href, finalUrl);
}

export async function searchSites(profiles: SiteProfile[], query: string): Promise<SearchResultItem[]> {
  const settled = await Promise.allSettled(profiles.map((p) => searchSite(p, query)));
  return settled.flatMap((r) => (r.status === "fulfilled" ? r.value : []));
}
