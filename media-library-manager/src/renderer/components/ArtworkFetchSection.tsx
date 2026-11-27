import { useEffect, useState } from "react";
import type { AppSettings, MediaItem, ScanProgressEvent } from "../../shared/types.js";
import FetchProgressDialog from "./FetchProgressDialog.js";
import LibraryFolderPicker, { itemsInFolder } from "./LibraryFolderPicker.js";

/** Settings → "Fetch artwork & info" for a library folder you browse to (the whole library by default). */
export default function ArtworkFetchSection({ settings }: { settings: AppSettings }) {
  const [items, setItems] = useState<MediaItem[]>([]);
  const [path, setPath] = useState<string[]>([]);
  const [picking, setPicking] = useState(false);
  const [includeSubfolders, setIncludeSubfolders] = useState(true);
  const [forceRefetch, setForceRefetch] = useState(false);
  const [fetching, setFetching] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showProgress, setShowProgress] = useState(false);
  const [order, setOrder] = useState<string[]>([]);
  const [byId, setById] = useState<Record<string, ScanProgressEvent>>({});

  useEffect(() => {
    let alive = true;
    const load = () => void window.api.getLibraryItems().then((all) => alive && setItems(all));
    load();
    const off = window.api.onLibraryChanged(load);
    return () => {
      alive = false;
      off();
    };
  }, []);

  const scope = itemsInFolder(items, settings, path, includeSubfolders);

  async function fetchInfo() {
    setFetching(true);
    setError(null);
    setOrder([]);
    setById({});
    setShowProgress(true);
    const unsubscribe = window.api.onMetadataScanProgress((event) => {
      setOrder((prev) => (prev.includes(event.itemId) ? prev : [...prev, event.itemId]));
      setById((prev) => ({ ...prev, [event.itemId]: event }));
    });
    try {
      setItems(await window.api.scanMetadata(scope.map((i) => i.id), forceRefetch));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      unsubscribe();
      setFetching(false);
    }
  }

  return (
    <section className="settings-section">
      <h2>Fetch artwork &amp; info</h2>
      <p className="hint">Looks up posters, titles and years for the files in a folder of your library.</p>
      <div className="form-row folder-choice">
        <span className="folder-choice-path">📁 {path.length === 0 ? "Whole library" : path.join(" / ")}</span>
        <button className="btn" onClick={() => setPicking(true)}>
          Browse…
        </button>
      </div>
      <div className="form-row">
        <label className="checkbox-label">
          <input type="checkbox" checked={includeSubfolders} onChange={(e) => setIncludeSubfolders(e.target.checked)} />
          Include subfolders
        </label>
        <label className="checkbox-label" title="Re-fetch everything in scope, overriding whatever was found before.">
          <input type="checkbox" checked={forceRefetch} onChange={(e) => setForceRefetch(e.target.checked)} />
          Force
        </label>
      </div>
      <div className="form-actions">
        <button className="btn btn-primary" onClick={() => void fetchInfo()} disabled={fetching || scope.length === 0}>
          {fetching ? "Fetching artwork & info…" : `Fetch artwork & info (${scope.length} file${scope.length === 1 ? "" : "s"})`}
        </button>
      </div>
      {error && <p className="error">{error}</p>}
      {picking && (
        <LibraryFolderPicker
          items={items}
          settings={settings}
          initialPath={path}
          onClose={() => setPicking(false)}
          onPick={(next) => {
            setPath(next);
            setPicking(false);
          }}
        />
      )}
      {showProgress && (
        <FetchProgressDialog
          running={fetching}
          order={order}
          eventsById={byId}
          onCancel={() => window.api.cancelMetadataScan()}
          onClose={() => setShowProgress(false)}
        />
      )}
    </section>
  );
}
