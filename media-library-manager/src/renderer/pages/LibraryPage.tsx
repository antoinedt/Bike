import { useEffect, useState } from "react";
import type { AppSettings, MediaItem } from "../../shared/types.js";
import FilterBar from "../components/FilterBar.js";
import MediaBrowser from "../components/MediaBrowser.js";
import StreamPlayer from "../components/StreamPlayer.js";

interface ActiveStream {
  item: MediaItem;
  url: string;
  token: string;
}

export default function LibraryPage({ settings }: { settings: AppSettings }) {
  const [items, setItems] = useState<MediaItem[]>([]);
  const [filter, setFilter] = useState("");
  const [selectedTags, setSelectedTags] = useState<string[]>([]);
  const [scanningFolders, setScanningFolders] = useState(false);
  const [scanningShares, setScanningShares] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stream, setStream] = useState<ActiveStream | null>(null);
  const [downloading, setDownloading] = useState<string | null>(null);
  const [downloadProgress, setDownloadProgress] = useState<number | null>(null);

  useEffect(() => {
    void window.api.getLibraryItems().then(setItems);
  }, []);

  // Closes whichever stream was active whenever a new one starts, and on unmount.
  useEffect(() => {
    if (!stream) return;
    return () => {
      void window.api.closeNetworkStream(stream.token);
    };
  }, [stream]);

  async function scanFolders() {
    setScanningFolders(true);
    setError(null);
    try {
      setItems(await window.api.scanLibrary());
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setScanningFolders(false);
    }
  }

  async function scanShares() {
    setScanningShares(true);
    setError(null);
    try {
      setItems(await window.api.scanNetwork());
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setScanningShares(false);
    }
  }

  async function open(item: MediaItem) {
    setError(null);
    try {
      if (item.source.type === "local") {
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

  function toggleTag(tag: string) {
    setSelectedTags((prev) => (prev.includes(tag) ? prev.filter((t) => t !== tag) : [...prev, tag]));
  }

  function handleItemUpdated(updated: MediaItem) {
    setItems((prev) => prev.map((i) => (i.id === updated.id ? updated : i)));
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Library</h1>
        <div className="page-actions">
          <input
            className="text-input"
            placeholder="Filter…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
          <button className="btn btn-primary" onClick={() => void scanFolders()} disabled={scanningFolders}>
            {scanningFolders ? "Scanning…" : "Scan folders"}
          </button>
          <button className="btn btn-primary" onClick={() => void scanShares()} disabled={scanningShares}>
            {scanningShares ? "Scanning…" : "Scan shares"}
          </button>
        </div>
      </div>
      {settings.localFolders.length === 0 && settings.networkShares.length === 0 && (
        <p className="hint">
          Nothing configured yet. Add a local folder or an SMB share in <strong>Settings</strong> to
          start scanning.
        </p>
      )}
      {error && <p className="error">{error}</p>}
      {downloading && (
        <p className="hint-inline">
          Downloading… {downloadProgress != null ? `${Math.round(downloadProgress * 100)}%` : ""}
        </p>
      )}
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
        onItemUpdated={handleItemUpdated}
        onItemsChanged={setItems}
      />
      {stream && <StreamPlayer item={stream.item} url={stream.url} onClose={() => setStream(null)} />}
    </div>
  );
}
