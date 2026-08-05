import { useState } from "react";
import type { AppSettings, SearchResultItem } from "../../shared/types.js";
import ResultsTable from "../components/ResultsTable.js";

export default function SearchPage({ settings }: { settings: AppSettings }) {
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [results, setResults] = useState<SearchResultItem[]>([]);
  const [searching, setSearching] = useState(false);
  const [downloadingUrl, setDownloadingUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  function toggleProfile(id: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function runSearch() {
    if (!query.trim()) return;
    setSearching(true);
    setError(null);
    setNotice(null);
    try {
      const profileIds = selected.size > 0 ? Array.from(selected) : undefined;
      const found = await window.api.runSearch(query.trim(), profileIds);
      setResults(found);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSearching(false);
    }
  }

  async function download(result: SearchResultItem) {
    setDownloadingUrl(result.downloadUrl);
    setError(null);
    setNotice(null);
    try {
      await window.api.downloadTorrent(result.downloadUrl);
      setNotice(`Sent "${result.title}" to your default torrent app.`);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setDownloadingUrl(null);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Search</h1>
      </div>

      {settings.siteProfiles.length === 0 ? (
        <p className="hint">
          No search sites configured yet. Add a site profile in <strong>Settings</strong> first.
        </p>
      ) : (
        <>
          <div className="profile-toggles">
            {settings.siteProfiles.map((p) => (
              <label key={p.id} className="profile-toggle">
                <input type="checkbox" checked={selected.has(p.id)} onChange={() => toggleProfile(p.id)} />
                {p.label}
              </label>
            ))}
            <span className="hint-inline">
              {selected.size === 0 ? "Searching all configured sites" : `Searching ${selected.size} site(s)`}
            </span>
          </div>

          <form
            className="search-bar"
            onSubmit={(e) => {
              e.preventDefault();
              void runSearch();
            }}
          >
            <input
              className="text-input"
              placeholder="Search term…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <button className="btn btn-primary" type="submit" disabled={searching}>
              {searching ? "Searching…" : "Search"}
            </button>
          </form>

          {error && <p className="error">{error}</p>}
          {notice && <p className="notice">{notice}</p>}

          <ResultsTable results={results} onDownload={(r) => void download(r)} downloadingUrl={downloadingUrl} />
        </>
      )}
    </div>
  );
}
