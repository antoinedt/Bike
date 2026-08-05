import { useEffect, useMemo, useState } from "react";
import type { AppSettings, MediaItem } from "../../shared/types.js";
import MediaGrid from "../components/MediaGrid.js";

export default function NetworkPage({ settings }: { settings: AppSettings }) {
  const [items, setItems] = useState<MediaItem[]>([]);
  const [filter, setFilter] = useState("");
  const [scanning, setScanning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void window.api.getLibraryItems().then((all) => setItems(all.filter((i) => i.source.type === "network")));
  }, []);

  const filtered = useMemo(
    () => items.filter((i) => i.name.toLowerCase().includes(filter.toLowerCase())),
    [items, filter]
  );

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
        </div>
      </div>
      {settings.networkShares.length === 0 && (
        <p className="hint">
          No SMB shares configured yet. Add one in <strong>Settings</strong> to browse it here.
        </p>
      )}
      <p className="hint">
        Opening a network file downloads it to a temp folder first, then hands it to your default
        player — playback isn't streamed directly from the share.
      </p>
      {error && <p className="error">{error}</p>}
      <MediaGrid items={filtered} onOpen={(item) => void openItem(item)} />
    </div>
  );
}
