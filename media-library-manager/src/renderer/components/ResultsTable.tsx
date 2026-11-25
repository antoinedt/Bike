import type { SearchResultItem } from "../../shared/types.js";

export default function ResultsTable({
  results,
  onDownload,
  onOpenExternally,
  onSendTo,
  downloadingUrl
}: {
  results: SearchResultItem[];
  /** Downloads in-app (Downloads page). */
  onDownload: (result: SearchResultItem) => void;
  /** Hands the link to the OS's default torrent app instead. */
  onOpenExternally: (result: SearchResultItem) => void;
  /** "Download on another device"; absent when that's not set up. */
  onSendTo?: (result: SearchResultItem) => void;
  downloadingUrl: string | null;
}) {
  if (results.length === 0) {
    return <div className="empty-state">No results yet.</div>;
  }

  return (
    <table className="results-table">
      <thead>
        <tr>
          <th>Title</th>
          <th>Source</th>
          <th>Size</th>
          <th>Seeders</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        {results.map((r, i) => (
          <tr key={`${r.sourceProfileId}-${i}`}>
            <td className="results-title" title={r.title}>
              {r.title}
            </td>
            <td>{r.sourceProfileLabel}</td>
            <td>{r.size || "—"}</td>
            <td>{r.seeders || "—"}</td>
            <td className="results-actions">
              <button
                className="btn btn-small btn-primary"
                onClick={() => onDownload(r)}
                disabled={downloadingUrl === r.downloadUrl}
              >
                {downloadingUrl === r.downloadUrl ? "Adding…" : "Download"}
              </button>
              <button
                className="btn btn-small"
                onClick={() => onOpenExternally(r)}
                disabled={downloadingUrl === r.downloadUrl}
                title="Open in your default torrent app instead"
              >
                Open in torrent app
              </button>
              {onSendTo && (
                <button className="btn btn-small" onClick={() => onSendTo(r)} title="Start this download on another device">
                  Send to…
                </button>
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
