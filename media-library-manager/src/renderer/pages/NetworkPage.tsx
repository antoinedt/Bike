import { useEffect, useState } from "react";
import type { AppSettings, MediaItem } from "../../shared/types.js";
import MediaBrowser from "../components/MediaBrowser.js";
import StreamPlayer from "../components/StreamPlayer.js";

interface ActiveStream {
  item: MediaItem;
  url: string;
  token: string;
}

export default function NetworkPage({ settings }: { settings: AppSettings }) {
  const [items, setItems] = useState<MediaItem[]>([]);
  const [filter, setFilter] = useState("");
  const [scanning, setScanning] = useState(false);
  const [fetchingInfo, setFetchingInfo] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stream, setStream] = useState<ActiveStream | null>(null);
  const [downloading, setDownloading] = useState<string | null>(null);

  useEffect(() => {
    void window.api.getLibraryItems().then((all) => setItems(all.filter((i) => i.source.type === "network")));
  }, []);

  // Closes whichever stream was active whenever a new one starts, and on unmount.
  useEffect(() => {
    if (!stream) return;
    return () => {
      void window.api.closeNetworkStream(stream.token);
    };
  }, [stream]);

  async function scan() {
    setScanning(true);
    setError(null);
    try {
      const all = await window.api.scanNetwork();
      setItems(all.filter((i) => i.source.type === "network"));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setScanning(false);
    }
  }

  async function play(item: MediaItem) {
    setError(null);
    try {
      const { url, token } = await window.api.openNetworkStream(item);
      setStream({ item, url, token });
    } catch (err) {
      setError((err as Error).message);
    }
  }

  async function fetchInfo() {
    setFetchingInfo(true);
    setError(null);
    try {
      const all = await window.api.scanMetadata();
      setItems(all.filter((i) => i.source.type === "network"));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setFetchingInfo(false);
    }
  }

  async function download(item: MediaItem) {
    setError(null);
    setDownloading(item.id);
    try {
      await window.api.downloadNetworkItem(item);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setDownloading(null);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Network</h1>
        <div className="page-actions">
          <input
            className="text-input"
            placeholder="Filter…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
          <button className="btn btn-primary" onClick={() => void scan()} disabled={scanning}>
            {scanning ? "Scanning…" : "Scan shares"}
          </button>
          <button className="btn" onClick={() => void fetchInfo()} disabled={fetchingInfo}>
            {fetchingInfo ? "Fetching artwork & info…" : "Fetch artwork & info"}
          </button>
        </div>
      </div>
      {settings.networkShares.length === 0 && (
        <p className="hint">
          No SMB shares configured yet. Add one in <strong>Settings</strong> to browse it here.
        </p>
      )}
      <p className="hint">
        Playback streams live from the share (byte-range reads, seekable) — nothing is downloaded
        to disk first. "Fetch artwork & info" looks up title/year/cover art and each file's length
        and writes a <code>.metadata.json</code> sidecar onto the share itself, so any device
        browsing it sees the same info without re-fetching.
      </p>
      {error && <p className="error">{error}</p>}
      {downloading && <p className="hint-inline">Downloading…</p>}
      <MediaBrowser
        items={items}
        settings={settings}
        filter={filter}
        onOpen={(item) => void play(item)}
        onDownload={(item) => void download(item)}
      />
      {stream && <StreamPlayer item={stream.item} url={stream.url} onClose={() => setStream(null)} />}
    </div>
  );
}
