import * as cheerio from "cheerio";
import type { SearchResultItem, SiteProfile } from "../../shared/types.js";
import { fetchRenderedHtml } from "./headlessFetch.js";
import { log } from "../log.js";

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
  const started = Date.now();
  log("search", `${profile.label}: fetching ${searchUrl}${profile.useHeadlessBrowser ? " (JavaScript site)" : ""}`);
  const { html, finalUrl } = await fetchHtml(searchUrl, profile.useHeadlessBrowser, profile.resultItemSelector);
  const $ = cheerio.load(html);

  const rows = $(profile.resultItemSelector).toArray();
  log(
    "search",
    `${profile.label}: got ${html.length} bytes from ${finalUrl} in ${Date.now() - started} ms; ` +
      `"${profile.resultItemSelector}" matched ${rows.length} row(s)` +
      (rows.length === 0 ? `; page title: "${$("title").first().text().trim()}"` : "")
  );
  if (rows.length === 0) {
    const text = $("body").text().replace(/\s+/g, " ").trim().slice(0, 300);
    log("search", `${profile.label}: page text: "${text}"; html starts: ${html.slice(0, 600).replace(/\s+/g, " ")}`);
  }
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

  if (rows.length > 0) {
    const first = $(rows[0]);
    log(
      "search",
      `${profile.label}: ${candidates.length} row(s) had a title and link. First row: ` +
        `title="${first.find(profile.titleSelector).first().text().trim().slice(0, 80)}" ` +
        `link="${(first.find(profile.linkSelector).first().attr("href") ?? "").slice(0, 80)}"`
    );
  }

  const resolved = await resolveDownloadUrls(candidates, profile);
  if (profile.detailPageLinkSelector) {
    log("search", `${profile.label}: ${resolved.length} of ${candidates.length} resolved via detail pages`);
  }

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
      } catch (err) {
        // drop this one, matching the previous "continue" behavior
        log("search", `${profile.label}: detail page ${candidate.downloadUrl} failed: ${(err as Error).message}`);
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
  log("search", `"${query}" on ${profiles.map((p) => p.label).join(", ") || "no sites"}`);
  const settled = await Promise.allSettled(profiles.map((p) => searchSite(p, query)));
  settled.forEach((r, i) => {
    if (r.status === "rejected") log("search", `${profiles[i].label}: FAILED: ${(r.reason as Error)?.message ?? r.reason}`);
  });
  // Every site failed: say why instead of showing a silent "No results".
  if (settled.length > 0 && settled.every((r) => r.status === "rejected")) {
    const reason = (settled[0] as PromiseRejectedResult).reason as Error;
    throw new Error(`${profiles[0].label}: ${reason?.message ?? reason}`);
  }
  return settled.flatMap((r) => (r.status === "fulfilled" ? r.value : []));
}
