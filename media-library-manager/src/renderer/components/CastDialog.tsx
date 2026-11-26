import { useEffect, useState } from "react";
import type { CastTarget, MediaItem } from "../../shared/types.js";

/** Pick a TV to play an item on: Google TV / Chromecast, or a DLNA TV such as a Samsung. */
export default function CastDialog({ item, onClose }: { item: MediaItem; onClose: () => void }) {
  const [targets, setTargets] = useState<CastTarget[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState<string | null>(null);

  async function search() {
    setTargets(null);
    setError(null);
    try {
      setTargets(await window.api.discoverCastTargets());
    } catch (err) {
      setError((err as Error).message);
      setTargets([]);
    }
  }

  useEffect(() => {
    void search();
  }, []);

  async function cast(target: CastTarget) {
    setStarting(target.id);
    try {
      await window.api.castItem(target.id, item);
      onClose();
    } catch (err) {
      setError((err as Error).message.replace(/^Error invoking remote method '[^']+': (Error: )?/, ""));
      setStarting(null);
    }
  }

  return (
    <div className="player-overlay" onClick={onClose}>
      <div className="confirm-dialog copy-dialog" onClick={(e) => e.stopPropagation()}>
        <h3>Play “{item.title || item.name}” on…</h3>
        {targets === null && <p className="hint">Looking for TVs on your network…</p>}
        {targets?.length === 0 && !error && (
          <p className="hint">No TVs found. Check the TV is on and on the same network as this computer.</p>
        )}
        {targets && targets.length > 0 && (
          <ul className="copy-list">
            {targets.map((t) => (
              <li key={t.id}>
                <button className="folder-card" disabled={starting !== null} onClick={() => void cast(t)}>
                  📺 {t.name}
                  <span className="device-seen">
                    {t.kind === "chromecast" ? "Google Cast" : "DLNA"}
                    {starting === t.id ? " · starting…" : ""}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
        {error && <p className="error">{error}</p>}
        <div className="form-actions">
          <button className="btn" onClick={() => void search()} disabled={targets === null}>
            Search again
          </button>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}
