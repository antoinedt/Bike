import { useEffect, useState } from "react";
import type { AppSettings, MediaItem } from "../../shared/types.js";
import MediaBrowser from "../components/MediaBrowser.js";

export default function LibraryPage({ settings }: { settings: AppSettings }) {
  const [items, setItems] = useState<MediaItem[]>([]);
  const [filter, setFilter] = useState("");
  const [scanning, setScanning] = useState(false);
  const [fetchingInfo, setFetchingInfo] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void window.api.getLibraryItems().then((all) => setItems(all.filter((i) => i.source.type === "local")));
  }, []);

  async function scan() {
    setScanning(true);
    setError(null);
    try {
      const all = await window.api.scanLibrary();
      setItems(all.filter((i) => i.source.type === "local"));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setScanning(false);
    }
  }

  async function fetchInfo() {
    setFetchingInfo(true);
    setError(null);
    try {
      const all = await window.api.scanMetadata();
      setItems(all.filter((i) => i.source.type === "local"));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setFetchingInfo(false);
    }
  }

  async function openItem(item: MediaItem) {
    try {
      await window.api.openItem(item);
    } catch (err) {
      setError((err as Error).message);
    }
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
          <button className="btn btn-primary" onClick={() => void scan()} disabled={scanning}>
            {scanning ? "Scanning…" : "Scan folders"}
          </button>
          <button className="btn" onClick={() => void fetchInfo()} disabled={fetchingInfo}>
            {fetchingInfo ? "Fetching artwork & info…" : "Fetch artwork & info"}
          </button>
        </div>
      </div>
      <p className="hint">
        Looks up title/year/cover art (iTunes, falling back to a frame pulled from the video
        itself when nothing matches — most useful for home videos, which iTunes won't know about)
        and each file's length, for the whole library (local and network). Saves a
        <code> .metadata.json</code> file next to each media file — uploaded to the share for
        network items — so it only needs to happen once per file, from any device.
      </p>
      {settings.localFolders.length === 0 && (
        <p className="hint">
          No folders configured yet. Add one in <strong>Settings</strong> to start scanning.
        </p>
      )}
      {error && <p className="error">{error}</p>}
      <MediaBrowser items={items} settings={settings} filter={filter} onOpen={(item) => void openItem(item)} />
    </div>
  );
}
