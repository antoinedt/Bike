import { useEffect, useMemo, useState } from "react";
import type { AppSettings, MediaItem } from "../../shared/types.js";
import MediaGrid from "../components/MediaGrid.js";

export default function LibraryPage({ settings }: { settings: AppSettings }) {
  const [items, setItems] = useState<MediaItem[]>([]);
  const [filter, setFilter] = useState("");
  const [scanning, setScanning] = useState(false);
  const [fetchingArtwork, setFetchingArtwork] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void window.api.getLibraryItems().then((all) => setItems(all.filter((i) => i.source.type === "local")));
  }, []);

  const filtered = useMemo(
    () => items.filter((i) => i.name.toLowerCase().includes(filter.toLowerCase())),
    [items, filter]
  );

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

  async function fetchArtwork() {
    setFetchingArtwork(true);
    setError(null);
    try {
      const all = await window.api.fetchArtwork();
      setItems(all.filter((i) => i.source.type === "local"));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setFetchingArtwork(false);
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
          <button className="btn" onClick={() => void fetchArtwork()} disabled={fetchingArtwork}>
            {fetchingArtwork ? "Fetching artwork…" : "Fetch artwork"}
          </button>
        </div>
      </div>
      <p className="hint">
        Fetch artwork looks up covers/posters via the iTunes catalog based on each file's name —
        best-effort, and covers the whole library (local and network).
      </p>
      {settings.localFolders.length === 0 && (
        <p className="hint">
          No folders configured yet. Add one in <strong>Settings</strong> to start scanning.
        </p>
      )}
      {error && <p className="error">{error}</p>}
      <MediaGrid items={filtered} onOpen={(item) => void openItem(item)} />
    </div>
  );
}
