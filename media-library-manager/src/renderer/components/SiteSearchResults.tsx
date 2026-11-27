import { useEffect, useRef, useState } from "react";
import type { AppSettings, SearchResultItem } from "../../shared/types.js";
import ResultsTable from "./ResultsTable.js";
import SendToDialog from "./SendToDialog.js";

/** A search the Library's Search button asked for; a new `id` runs it again. */
export interface SiteSearchRequest {
  query: string;
  id: number;
}

/**
 * Results from your search sites, listed under the Library's own matches. Runs whenever the
 * Library sends a new request; ticking sites narrows (and re-runs) the search.
 */
export default function SiteSearchResults({
  settings,
  request,
  onOpenDownloads
}: {
  settings: AppSettings;
  request: SiteSearchRequest;
  onOpenDownloads: () => void;
}) {
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [results, setResults] = useState<SearchResultItem[]>([]);
  const [searching, setSearching] = useState(false);
  const [downloadingUrl, setDownloadingUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [addedToDownloads, setAddedToDownloads] = useState(false);
  const [sendingResult, setSendingResult] = useState<SearchResultItem | null>(null);
  // Only the latest search's results are shown, even if an older one finishes later.
  const latest = useRef(0);

  async function run(query: string, sites: Set<string>) {
    const token = ++latest.current;
    setSearching(true);
    setError(null);
    setNotice(null);
    setResults([]);
    try {
      const found = await window.api.runSearch(query, sites.size > 0 ? Array.from(sites) : undefined);
      if (token === latest.current) setResults(found);
    } catch (err) {
      if (token === latest.current) setError((err as Error).message.replace(/^Error invoking remote method '[^']+': (Error: )?/, ""));
    } finally {
      if (token === latest.current) setSearching(false);
    }
  }

  useEffect(() => {
    void run(request.query, selected);
    // A new request (Search button) runs the search; site toggles re-run it themselves.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [request.id]);

  function toggleSite(id: string) {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSelected(next);
    void run(request.query, next);
  }

  async function download(result: SearchResultItem, externally: boolean) {
    setDownloadingUrl(result.downloadUrl);
    setError(null);
    setNotice(null);
    setAddedToDownloads(false);
    try {
      if (externally) {
        await window.api.downloadTorrent(result.downloadUrl);
        setNotice(`Sent "${result.title}" to your default torrent app.`);
      } else {
        await window.api.addTorrent(result.downloadUrl);
        setNotice(`Added "${result.title}" to Downloads.`);
        setAddedToDownloads(true);
      }
    } catch (err) {
      setError((err as Error).message.replace(/^Error invoking remote method '[^']+': (Error: )?/, ""));
    } finally {
      setDownloadingUrl(null);
    }
  }

  const status = searching
    ? `Searching for “${request.query}”… (slow sites can take up to 2 minutes)`
    : results.length === 0
      ? `No results for “${request.query}”.`
      : `${results.length} result${results.length === 1 ? "" : "s"} for “${request.query}”`;

  return (
    <section className="site-search">
      <div className="site-search-header">
        <h2>From your search sites</h2>
        <span className="hint-inline">{status}</span>
      </div>
      {settings.siteProfiles.length > 1 && (
        <div className="profile-toggles">
          {settings.siteProfiles.map((p) => (
            <label key={p.id} className="profile-toggle">
              <input type="checkbox" checked={selected.has(p.id)} onChange={() => toggleSite(p.id)} />
              {p.label}
            </label>
          ))}
          <span className="hint-inline">
            {selected.size === 0 ? "Asking every site" : `Asking ${selected.size} of ${settings.siteProfiles.length} sites`}
          </span>
        </div>
      )}
      {error && <p className="error">{error}</p>}
      {notice && (
        <p className="notice">
          {notice}
          {addedToDownloads && (
            <>
              {" "}
              <button className="link-button" onClick={onOpenDownloads}>
                View downloads
              </button>
            </>
          )}
        </p>
      )}
      {results.length > 0 && (
        <ResultsTable
          results={results}
          onDownload={(r) => void download(r, false)}
          onOpenExternally={(r) => void download(r, true)}
          onSendTo={settings.remoteShareId || settings.remoteLocalFolder ? setSendingResult : undefined}
          downloadingUrl={downloadingUrl}
        />
      )}
      {sendingResult && (
        <SendToDialog
          source={sendingResult.downloadUrl}
          title={sendingResult.title}
          onClose={() => setSendingResult(null)}
          onSent={(message) => {
            setSendingResult(null);
            setAddedToDownloads(false);
            setNotice(message);
          }}
        />
      )}
    </section>
  );
}
