import * as cheerio from "cheerio";
import type { SearchResultItem, SiteProfile } from "../../shared/types.js";
import { fetchRenderedHtml } from "./headlessFetch.js";

const USER_AGENT =
  "Mozilla/5.0 (Media Library Manager; +desktop app) media-library-manager/0.1";

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

/**
 * Runs a search against a user-configured site profile and parses the results with the
 * profile's CSS selectors. Only fetches sites the user explicitly configured in Settings.
 */
export async function searchSite(profile: SiteProfile, query: string): Promise<SearchResultItem[]> {
  const searchUrl = buildUrl(profile.searchUrlTemplate, query);
  const { html, finalUrl } = await fetchHtml(searchUrl, profile.useHeadlessBrowser, profile.resultItemSelector);
  const $ = cheerio.load(html);

  const rows = $(profile.resultItemSelector).toArray();
  const results: SearchResultItem[] = [];

  for (const row of rows) {
    const $row = $(row);
    const title = $row.find(profile.titleSelector).first().text().trim();
    const rawLink =
      $row.find(profile.linkSelector).first().attr("href") ??
      $row.find(profile.linkSelector).first().text().trim();
    if (!title || !rawLink) continue;

    let downloadUrl = resolveUrl(rawLink, finalUrl);
    const size = profile.sizeSelector ? $row.find(profile.sizeSelector).first().text().trim() : "";
    const seeders = profile.seedersSelector
      ? $row.find(profile.seedersSelector).first().text().trim()
      : "";

    if (profile.detailPageLinkSelector && !downloadUrl.startsWith("magnet:")) {
      try {
        downloadUrl = await resolveFromDetailPage(
          downloadUrl,
          profile.detailPageLinkSelector,
          profile.useHeadlessBrowser
        );
      } catch {
        continue;
      }
    }

    results.push({
      title,
      downloadUrl,
      size,
      seeders,
      sourceProfileId: profile.id,
      sourceProfileLabel: profile.label
    });
  }

  return results;
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
