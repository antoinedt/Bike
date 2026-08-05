package com.medialibrary.manager.search

import com.medialibrary.manager.model.SearchResultItem
import com.medialibrary.manager.model.SiteProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

private const val USER_AGENT = "Mozilla/5.0 (Media Library Manager Android; +mobile app) media-library-manager/0.1"
private const val TIMEOUT_MS = 15_000

/**
 * Runs a search against a user-configured site profile and parses the results with the
 * profile's CSS selectors — a Kotlin/Jsoup port of the desktop app's siteSearch.ts. Only
 * fetches sites the user explicitly added in Settings.
 */
class SiteSearchRepository {

    suspend fun searchSites(profiles: List<SiteProfile>, query: String): List<SearchResultItem> = coroutineScope {
        profiles.map { profile -> async { runCatching { searchSite(profile, query) }.getOrDefault(emptyList()) } }
            .map { it.await() }
            .flatten()
    }

    suspend fun searchSite(profile: SiteProfile, query: String): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = profile.searchUrlTemplate.replace("{query}", encodedQuery)
        val doc = fetch(searchUrl)

        val results = mutableListOf<SearchResultItem>()
        for (row in doc.select(profile.resultItemSelector)) {
            val title = row.select(profile.titleSelector).firstOrNull()?.text()?.trim().orEmpty()
            val linkEl = row.select(profile.linkSelector).firstOrNull() ?: continue
            var downloadUrl = linkEl.attr("abs:href").ifBlank { linkEl.text().trim() }
            if (title.isBlank() || downloadUrl.isBlank()) continue

            if (profile.detailPageLinkSelector.isNotBlank() && !downloadUrl.startsWith("magnet:")) {
                downloadUrl = runCatching { resolveFromDetailPage(downloadUrl, profile.detailPageLinkSelector) }
                    .getOrNull() ?: continue
            }

            val size = if (profile.sizeSelector.isNotBlank()) {
                row.select(profile.sizeSelector).firstOrNull()?.text()?.trim().orEmpty()
            } else ""
            val seeders = if (profile.seedersSelector.isNotBlank()) {
                row.select(profile.seedersSelector).firstOrNull()?.text()?.trim().orEmpty()
            } else ""

            results += SearchResultItem(title, downloadUrl, size, seeders, profile.id, profile.label)
        }
        results
    }

    private fun resolveFromDetailPage(detailPageUrl: String, selector: String): String {
        val doc = fetch(detailPageUrl)
        val href = doc.select(selector).firstOrNull()?.attr("abs:href")
        require(!href.isNullOrBlank()) { "No download link found on detail page via selector \"$selector\"" }
        return href
    }

    private fun fetch(url: String): Document =
        Jsoup.connect(url).userAgent(USER_AGENT).timeout(TIMEOUT_MS).get()
}
