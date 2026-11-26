import { useEffect, useState } from "react";
import type { CastTarget } from "../../shared/types.js";

/**
 * Pick a Google TV to cast a live channel to. Fizz's own Cast receiver does the Widevine playback
 * on the TV, so a DLNA TV (e.g. a plain Samsung) can't be used — only Chromecast / Google TV.
 */
export default function CastChannelDialog({ channelId, channelName, onClose }: { channelId: number; channelName: string; onClose: () => void }) {
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
    const result = await window.api.castChannel(target.id, channelId, channelName);
    if (result.ok) onClose();
    else {
      setError(result.error);
      setStarting(null);
    }
  }

  const googleTvs = (targets ?? []).filter((t) => t.kind === "chromecast");
  return (
    <div className="player-overlay" onClick={onClose}>
      <div className="confirm-dialog copy-dialog" onClick={(e) => e.stopPropagation()}>
        <h3>Cast “{channelName}” to…</h3>
        <p className="hint">Live channels need a Chromecast or Google TV.</p>
        {targets === null && <p className="hint">Looking for TVs on your network…</p>}
        {targets !== null && googleTvs.length === 0 && !error && (
          <p className="hint">No Google TVs found. Check the TV is on and on the same network as this computer.</p>
        )}
        {googleTvs.length > 0 && (
          <ul className="copy-list">
            {googleTvs.map((t) => (
              <li key={t.id}>
                <button className="folder-card" disabled={starting !== null} onClick={() => void cast(t)}>
                  📺 {t.name}
                  <span className="device-seen">Google Cast{starting === t.id ? " · starting…" : ""}</span>
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
