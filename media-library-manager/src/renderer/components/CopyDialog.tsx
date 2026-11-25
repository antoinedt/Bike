import { useEffect, useState } from "react";
import type { AppSettings, CopyTarget, MediaItem } from "../../shared/types.js";

type Location = { kind: "folder"; folderId: string; label: string } | { kind: "share"; shareId: string; label: string };

/**
 * "Copy to…": pick one of the library's local folders or network shares, browse into a folder
 * (or make a new one), or pick any folder on this computer. The copy itself runs in the
 * background; progress shows on the Downloads page.
 */
export default function CopyDialog({
  item,
  settings,
  onClose,
  onStarted
}: {
  item: MediaItem;
  settings: AppSettings;
  onClose: () => void;
  onStarted: (label: string) => void;
}) {
  const [location, setLocation] = useState<Location | null>(null);
  const [relPath, setRelPath] = useState<string[]>([]);
  const [folders, setFolders] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [newName, setNewName] = useState("");
  const [reload, setReload] = useState(0);

  const locations: Location[] = [
    ...settings.localFolders.map((f) => ({ kind: "folder" as const, folderId: f.id, label: f.label })),
    ...settings.networkShares.map((s) => ({ kind: "share" as const, shareId: s.id, label: s.label }))
  ];

  const target: CopyTarget | null = location
    ? location.kind === "folder"
      ? { kind: "folder", folderId: location.folderId, relPath: relPath.join("/") }
      : { kind: "share", shareId: location.shareId, relPath: relPath.join("/") }
    : null;
  const targetLabel = location ? [location.label, ...relPath].join("/") : "";

  useEffect(() => {
    if (!target) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    window.api
      .listCopyFolders(target)
      .then((names) => !cancelled && setFolders(names))
      .catch((err: Error) => !cancelled && setError(clean(err)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location, relPath.join("/"), reload]);

  async function start(to: CopyTarget, label: string) {
    try {
      await window.api.copyItem(item, to);
      onStarted(label);
    } catch (err) {
      setError(clean(err as Error));
    }
  }

  async function pickOther() {
    const picked = await window.api.pickCopyFolder();
    if (picked) await start({ kind: "path", path: picked }, picked);
  }

  async function createFolder() {
    if (!target || !newName.trim()) return;
    try {
      await window.api.createCopyFolder(target, newName.trim());
      setRelPath([...relPath, newName.trim()]);
      setNewName("");
      setReload((n) => n + 1);
    } catch (err) {
      setError(clean(err as Error));
    }
  }

  return (
    <div className="player-overlay" onClick={onClose}>
      <div className="confirm-dialog copy-dialog" onClick={(e) => e.stopPropagation()}>
        <h3>Copy “{item.title || item.name}” to…</h3>
        {!location ? (
          <ul className="copy-list">
            {locations.map((loc) => (
              <li key={loc.kind + (loc.kind === "folder" ? loc.folderId : loc.shareId)}>
                <button className="folder-card" onClick={() => { setLocation(loc); setRelPath([]); }}>
                  {loc.kind === "share" ? "🖧" : "📁"} {loc.label}
                </button>
              </li>
            ))}
            <li>
              <button className="folder-card" onClick={() => void pickOther()}>
                🗂 Another folder on this computer…
              </button>
            </li>
          </ul>
        ) : (
          <>
            <div className="copy-path">{targetLabel}</div>
            <ul className="copy-list">
              <li>
                <button className="folder-card" onClick={() => (relPath.length ? setRelPath(relPath.slice(0, -1)) : setLocation(null))}>
                  ← {relPath.length ? "Up one folder" : "All locations"}
                </button>
              </li>
              {loading && <li className="hint">Loading folders…</li>}
              {!loading &&
                folders.map((name) => (
                  <li key={name}>
                    <button className="folder-card" onClick={() => setRelPath([...relPath, name])}>
                      📁 {name}
                    </button>
                  </li>
                ))}
            </ul>
            <form
              className="search-bar"
              onSubmit={(e) => {
                e.preventDefault();
                void createFolder();
              }}
            >
              <input className="text-input" placeholder="New folder name" value={newName} onChange={(e) => setNewName(e.target.value)} />
              <button className="btn" type="submit" disabled={!newName.trim()}>
                New folder
              </button>
            </form>
          </>
        )}
        {error && <p className="error">{error}</p>}
        <div className="form-actions">
          {target && (
            <button className="btn btn-primary" onClick={() => void start(target, targetLabel)}>
              Copy here
            </button>
          )}
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}

function clean(err: Error): string {
  return err.message.replace(/^Error invoking remote method '[^']+': (Error: )?/, "");
}
