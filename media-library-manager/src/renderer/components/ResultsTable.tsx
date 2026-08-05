import type { SearchResultItem } from "../../shared/types.js";

export default function ResultsTable({
  results,
  onDownload,
  downloadingUrl
}: {
  results: SearchResultItem[];
  onDownload: (result: SearchResultItem) => void;
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
            <td>
              <button
                className="btn btn-small"
                onClick={() => onDownload(r)}
                disabled={downloadingUrl === r.downloadUrl}
              >
                {downloadingUrl === r.downloadUrl ? "Opening…" : "Download"}
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
