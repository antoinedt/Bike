import type { ScanProgressEvent } from "../../shared/types.js";

const STEP_ICON: Record<string, string> = { found: "✓", "not-found": "—", error: "⚠" };

/**
 * Live progress for a "Fetch artwork & info" run: one row per item, updated in place as events
 * arrive (started -> done/cached/error), with the full per-step log (iTunes/TMDb/Album art/Video
 * frame/Duration, each found/not-found/error) shown for a "done" item — this is the answer to
 * "why didn't this one get artwork": every lookup that was tried and what it returned, not just
 * a final yes/no. Stays open after the scan finishes so that log is still there to read; Cancel
 * is only offered while running, OK only once it's done.
 */
export default function FetchProgressDialog({
  running,
  order,
  eventsById,
  onCancel,
  onClose
}: {
  running: boolean;
  order: string[];
  eventsById: Record<string, ScanProgressEvent>;
  onCancel: () => void;
  onClose: () => void;
}) {
  const doneCount = order.filter((id) => eventsById[id]?.status !== "started").length;

  return (
    <div className="player-overlay">
      <div className="details-panel">
        <div className="details-header">
          <span className="player-title">
            Fetch artwork & info {order.length > 0 ? `(${doneCount}/${order.length})` : ""}
          </span>
        </div>
        <div className="fetch-log">
          {order.length === 0 && <p className="hint">Starting…</p>}
          {order.map((id) => {
            const event = eventsById[id];
            return (
              <div key={id} className="fetch-log-row">
                <div className="fetch-log-name">{event.itemName}</div>
                {event.status === "started" && <div className="fetch-log-detail hint-inline">Working…</div>}
                {event.status === "cached" && (
                  <div className="fetch-log-detail hint-inline">Used a previous result — no new lookup needed.</div>
                )}
                {event.status === "error" && <div className="fetch-log-detail error">⚠ {event.error}</div>}
                {event.status === "done" && (
                  <div className="fetch-log-detail">
                    {(event.log ?? []).map((entry, i) => (
                      <div key={i} className="fetch-log-step">
                        {STEP_ICON[entry.result]} {entry.step}: {entry.result === "found" ? "found" : entry.result === "not-found" ? "no match" : `error — ${entry.detail}`}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            );
          })}
        </div>
        <div className="form-actions">
          {running ? (
            <button className="btn" onClick={onCancel}>
              Cancel
            </button>
          ) : (
            <button className="btn btn-primary" onClick={onClose}>
              OK
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
