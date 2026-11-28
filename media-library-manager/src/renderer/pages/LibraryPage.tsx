import { useEffect, useState } from "react";
import type { AppSettings, LibraryScanStatus, MediaItem } from "../../shared/types.js";
import FilterBar from "../components/FilterBar.js";
import CastDialog from "../components/CastDialog.js";
import CopyDialog from "../components/CopyDialog.js";
import MediaBrowser from "../components/MediaBrowser.js";
import { IconButton } from "../components/Icon.js";
import SiteSearchResults, { type SiteSearchRequest } from "../components/SiteSearchResults.js";
import StreamPlayer from "../components/StreamPlayer.js";
import CinemaHome, { type ResumeEntry } from "../components/CinemaHome.js";
import MediaDetailsPanel from "../components/MediaDetailsPanel.js";

interface ActiveStream {
  item: MediaItem;
  url: string;
  token: string;
}

export default function LibraryPage({ settings, onOpenDownloads }: { settings: AppSettings; onOpenDownloads: () => void }) {
  const [copying, setCopying] = useState<MediaItem | null>(null);
  const [casting, setCasting] = useState<MediaItem | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [items, setItems] = useState<MediaItem[]>([]);
  const [filter, setFilter] = useState("");
  // The term last sent to the search sites with the Search button (null: no site search shown).
  const [siteSearch, setSiteSearch] = useState<SiteSearchRequest | null>(null);
  const [selectedTags, setSelectedTags] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [stream, setStream] = useState<ActiveStream | null>(null);
  const [downloading, setDownloading] = useState<string | null>(null);
  const [downloadProgress, setDownloadProgress] = useState<number | null>(null);

  const [scan, setScan] = useState<LibraryScanStatus>({ scanning: false, found: 0 });
  // Home (Cinema rows) or the folder browser; remembered between launches.
  const [view, setView] = useState<"home" | "folders">(() => {
    try {
      return localStorage.getItem("library.view") === "folders" ? "folders" : "home";
    } catch {
      return "home";
    }
  });
  const [resume, setResume] = useState<ResumeEntry[]>([]);
  const [detail, setDetail] = useState<MediaItem | null>(null);

  function changeView(next: "home" | "folders") {
    setView(next);
    try {
      localStorage.setItem("library.view", next);
    } catch {
      // not remembered; fine
    }
  }

  // "Continue watching" — refreshed when the library changes and whenever a video is closed.
  useEffect(() => {
    if (stream) return;
    void window.api.listResume().then(setResume).catch(() => {});
  }, [stream, items]);

  // The library refreshes itself in the background (at startup, and after a scan/copy/download),
  // announcing changes a few times a second while it fills in. Refetches never overlap: a change
  // that arrives mid-fetch just triggers one more fetch afterwards.
  useEffect(() => {
    let alive = true;
    let inFlight = false;
    let again = false;
    const refresh = async () => {
      if (inFlight) {
        again = true;
        return;
      }
      inFlight = true;
      do {
        again = false;
        const latest = await window.api.getLibraryItems();
        if (alive) setItems(latest);
      } while (again && alive);
      inFlight = false;
    };
    void refresh();
    void window.api.getLibraryScanStatus().then((s) => alive && setScan(s));
    const offChanged = window.api.onLibraryChanged(() => void refresh());
    const offStatus = window.api.onLibraryScanStatus(setScan);
    return () => {
      alive = false;
      offChanged();
      offStatus();
    };
  }, []);

  // Closes whichever stream was active whenever a new one starts, and on unmount.
  useEffect(() => {
    if (!stream) return;
    return () => {
      void window.api.closeNetworkStream(stream.token);
    };
  }, [stream]);

  async function open(item: MediaItem) {
    setError(null);
    try {
      // Images and other files open in their usual app; video and audio play here (so the
      // position is remembered and can be continued on another device).
      if (item.kind !== "video" && item.kind !== "audio") {
        await window.api.openItem(item);
      } else {
        const { url, token } = await window.api.openNetworkStream(item);
        setStream({ item, url, token });
      }
    } catch (err) {
      setError((err as Error).message);
    }
  }

  async function download(item: MediaItem) {
    setError(null);
    setDownloading(item.id);
    setDownloadProgress(null);
    const unsubscribe = window.api.onNetworkDownloadProgress(({ bytesWritten, totalBytes }) => {
      setDownloadProgress(totalBytes > 0 ? bytesWritten / totalBytes : null);
    });
    try {
      await window.api.downloadNetworkItem(item);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      unsubscribe();
      setDownloading(null);
      setDownloadProgress(null);
    }
  }

  // Typing filters the library as you go; Search (or Enter) also asks your search sites, whose
  // results are listed below the library's matches.
  function searchSites() {
    const query = filter.trim();
    if (!query) return;
    if (settings.siteProfiles.length === 0) {
      setNotice("Add a search site in Settings to search for things to download.");
      return;
    }
    setNotice(null);
    setSiteSearch((prev) => ({ query, id: (prev?.id ?? 0) + 1 }));
  }

  function onSearchTextChange(text: string) {
    setFilter(text);
    if (!text.trim()) setSiteSearch(null);
  }

  function toggleTag(tag: string) {
    setSelectedTags((prev) => (prev.includes(tag) ? prev.filter((t) => t !== tag) : [...prev, tag]));
  }

  function handleItemUpdated(updated: MediaItem) {
    setItems((prev) => prev.map((i) => (i.id === updated.id ? updated : i)));
  }

  return (
    <div className="page">
      <div className="page-header">
        <div className="view-switch" role="tablist" aria-label="Library view">
          <button role="tab" aria-selected={view === "home"} className={view === "home" ? "on" : ""} onClick={() => changeView("home")}>
            Home
          </button>
          <button role="tab" aria-selected={view === "folders"} className={view === "folders" ? "on" : ""} onClick={() => changeView("folders")}>
            Folders
          </button>
        </div>
        {scan.scanning && (
          <span className="scan-status" role="status">
            <span className="scan-spinner" aria-hidden="true" />
            Scanning… {scan.found > 0 ? `${scan.found} found` : ""}
          </span>
        )}
        <form
          className="page-actions"
          onSubmit={(e) => {
            e.preventDefault();
            searchSites();
          }}
        >
          <input
            className="text-input"
            type="search"
            placeholder="Search…"
            value={filter}
            onChange={(e) => onSearchTextChange(e.target.value)}
          />
          <IconButton icon="search" label="Search your sites too" className="btn-primary" type="submit" disabled={!filter.trim()} />
        </form>
      </div>
      {settings.localFolders.length === 0 && settings.networkShares.length === 0 && (
        <p className="hint">
          Nothing configured yet. Add a local folder or an SMB share in <strong>Settings</strong> — it's
          scanned automatically.
        </p>
      )}
      {error && <p className="error">{error}</p>}
      {notice && <p className="notice">{notice}</p>}
      {downloading && (
        <p className="hint-inline">
          Downloading… {downloadProgress != null ? `${Math.round(downloadProgress * 100)}%` : ""}
        </p>
      )}
      {view === "home" && !filter.trim() && selectedTags.length === 0 ? (
        <CinemaHome
          items={items}
          resume={resume}
          onPlay={(item) => void open(item)}
          onDetails={setDetail}
          onShowSearch={(query) => onSearchTextChange(query)}
          onBrowseFolders={() => changeView("folders")}
        />
      ) : (
      <>
      <FilterBar
        items={items}
        selectedTags={selectedTags}
        onToggleTag={toggleTag}
      />
      <MediaBrowser
        items={items}
        settings={settings}
        filter={filter}
        tagFilter={selectedTags}
        onOpen={(item) => void open(item)}
        onDownload={(item) => void download(item)}
        onCopy={setCopying}
        onCast={setCasting}
        onItemUpdated={handleItemUpdated}
      />
      </>
      )}
      {detail && (
        <MediaDetailsPanel
          item={detail}
          allTags={Array.from(new Set(items.flatMap((i) => i.tags ?? []))).sort()}
          onClose={() => setDetail(null)}
          onOpen={() => {
            void open(detail);
            setDetail(null);
          }}
          onDownload={detail.source.type === "network" ? () => { void download(detail); setDetail(null); } : undefined}
          onCopy={() => { setCopying(detail); setDetail(null); }}
          onCast={() => { setCasting(detail); setDetail(null); }}
          onItemUpdated={(updated) => {
            setDetail(updated);
            handleItemUpdated(updated);
          }}
        />
      )}
      {siteSearch && <SiteSearchResults settings={settings} request={siteSearch} onOpenDownloads={onOpenDownloads} />}
      {casting && <CastDialog item={casting} onClose={() => setCasting(null)} />}
      {copying && (
        <CopyDialog
          item={copying}
          settings={settings}
          onClose={() => setCopying(null)}
          onStarted={(label) => {
            setCopying(null);
            setNotice(`Copying "${copying.name}" to ${label} — progress is on the Downloads page.`);
          }}
        />
      )}
      {stream && <StreamPlayer item={stream.item} url={stream.url} onClose={() => setStream(null)} />}
    </div>
  );
}
