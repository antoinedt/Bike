import { useEffect, useState } from "react";
import type { CopyJob, TorrentStatus } from "../../shared/types.js";
import { formatDuration, formatSize } from "../lib/format.js";
import SendToDialog from "../components/SendToDialog.js";

const STATE_LABEL: Record<TorrentStatus["state"], string> = {
  metadata: "Getting file list…",
  downloading: "Downloading",
  paused: "Paused",
  done: "Done",
  error: "Failed"
};

function formatSpeed(bytesPerSecond: number): string {
  return bytesPerSecond > 0 ? `${formatSize(bytesPerSecond)}/s` : "—";
}

export default function DownloadsPage({ onOpenSettings, remoteEnabled }: { onOpenSettings: () => void; remoteEnabled: boolean }) {
  const [torrents, setTorrents] = useState<TorrentStatus[]>([]);
  const [folder, setFolder] = useState("");
  const [source, setSource] = useState("");
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [removing, setRemoving] = useState<TorrentStatus | null>(null);
  const [deleteFiles, setDeleteFiles] = useState(false);
  const [copies, setCopies] = useState<CopyJob[]>([]);
  const [sendingSource, setSendingSource] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    void window.api.getTorrents().then(setTorrents);
    void window.api.getTorrentDownloadFolder().then(setFolder);
    void window.api.getCopies().then(setCopies);
    const offCopies = window.api.onCopyUpdate(setCopies);
    const offTorrents = window.api.onTorrentUpdate(setTorrents);
    return () => {
      offCopies();
      offTorrents();
    };
  }, []);

  async function run(action: () => Promise<unknown>) {
    setError(null);
    try {
      await action();
    } catch (err) {
      setError((err as Error).message.replace(/^Error invoking remote method '[^']+': (Error: )?/, ""));
    }
  }

  async function addFromSource() {
    if (!source.trim()) return;
    setAdding(true);
    await run(async () => {
      await window.api.addTorrent(source.trim());
      setSource("");
    });
    setAdding(false);
  }

  async function confirmRemove() {
    if (!removing) return;
    const target = removing;
    setRemoving(null);
    await run(async () => setTorrents(await window.api.removeTorrent(target.id, deleteFiles)));
    setDeleteFiles(false);
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Downloads</h1>
      </div>

      <form
        className="search-bar"
        onSubmit={(e) => {
          e.preventDefault();
          void addFromSource();
        }}
      >
        <input
          className="text-input"
          placeholder="Paste a magnet link or a link to a .torrent file…"
          value={source}
          onChange={(e) => setSource(e.target.value)}
        />
        <button className="btn btn-primary" type="submit" disabled={adding || !source.trim()}>
          {adding ? "Adding…" : "Download"}
        </button>
        <button className="btn" type="button" onClick={() => void run(() => window.api.addTorrentFile())}>
          Open .torrent file…
        </button>
        {remoteEnabled && (
          <button className="btn" type="button" disabled={!source.trim()} onClick={() => setSendingSource(source.trim())} title="Start this download on another device">
            Send to…
          </button>
        )}
      </form>
      <p className="hint">
        Saving to <code>{folder}</code> ·{" "}
        <button className="link-button" onClick={onOpenSettings}>
          change
        </button>
        . If that folder is one of your Library folders, finished downloads appear in the Library automatically.
        Downloads stop once complete (no seeding afterwards). Only download content you're allowed to.
      </p>
      {error && <p className="error">{error}</p>}
      {notice && <p className="notice">{notice}</p>}
      {sendingSource && (
        <SendToDialog
          source={sendingSource}
          onClose={() => setSendingSource(null)}
          onSent={(message) => {
            setSendingSource(null);
            setSource("");
            setNotice(message);
          }}
        />
      )}

      {torrents.length === 0 ? (
        <div className="empty-state">No downloads yet. Use Download on a search result, or paste a magnet link above.</div>
      ) : (
        <ul className="download-list">
          {torrents.map((t) => (
            <li key={t.id} className={`download-row download-${t.state}`}>
              <div className="download-main">
                <div className="download-name" title={t.name}>
                  {t.name}
                </div>
                <div className="download-progress">
                  <div className="download-progress-fill" style={{ width: `${Math.round(t.progress * 100)}%` }} />
                </div>
                <div className="download-meta">
                  <span className="download-state">{STATE_LABEL[t.state]}</span>
                  <span>
                    {Math.floor(t.progress * 100)}%{t.length > 0 && ` · ${formatSize(t.downloaded)} of ${formatSize(t.length)}`}
                  </span>
                  {(t.state === "downloading" || t.state === "metadata") && (
                    <>
                      <span>↓ {formatSpeed(t.downloadSpeed)}</span>
                      <span>↑ {formatSpeed(t.uploadSpeed)}</span>
                      <span>
                        {t.peers} peer{t.peers === 1 ? "" : "s"}
                      </span>
                      {t.timeRemainingMs !== null && <span>{formatDuration(t.timeRemainingMs / 1000)} left</span>}
                    </>
                  )}
                  {t.error && <span className="download-error">{t.error}</span>}
                </div>
              </div>
              <div className="download-actions">
                {(t.state === "downloading" || t.state === "metadata") && (
                  <button className="btn btn-small" onClick={() => void run(async () => setTorrents(await window.api.pauseTorrent(t.id)))}>
                    Pause
                  </button>
                )}
                {(t.state === "paused" || t.state === "error") && (
                  <button className="btn btn-small" onClick={() => void run(async () => setTorrents(await window.api.resumeTorrent(t.id)))}>
                    {t.state === "error" ? "Retry" : "Resume"}
                  </button>
                )}
                <button className="btn btn-small" onClick={() => void run(() => window.api.openTorrentFolder(t.id))}>
                  Show in folder
                </button>
                <button className="btn btn-small btn-danger" onClick={() => setRemoving(t)}>
                  Remove
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {copies.length > 0 && (
        <>
          <div className="page-header copies-header">
            <h2>Copies</h2>
            {copies.some((c) => c.state === "done" || c.state === "failed") && (
              <button className="btn btn-small" onClick={() => void window.api.clearFinishedCopies().then(setCopies)}>
                Clear finished
              </button>
            )}
          </div>
          <ul className="download-list">
            {[...copies].reverse().map((c) => (
              <li key={c.id} className={`download-row download-${c.state === "done" ? "done" : c.state === "failed" ? "error" : "downloading"}`}>
                <div className="download-main">
                  <div className="download-name" title={c.itemName}>
                    {c.itemName}
                  </div>
                  <div className="download-progress">
                    <div
                      className="download-progress-fill"
                      style={{ width: `${c.totalBytes > 0 ? Math.round((c.copiedBytes / c.totalBytes) * 100) : c.state === "done" ? 100 : 0}%` }}
                    />
                  </div>
                  <div className="download-meta">
                    <span className="download-state">
                      {c.state === "queued" && `Waiting · to ${c.targetLabel}`}
                      {c.state === "running" && `Copying to ${c.targetLabel}`}
                      {c.state === "done" && `Copied to ${c.targetLabel}${c.savedAs ? ` as “${c.savedAs}”` : ""}`}
                      {c.state === "failed" && `Failed: ${c.error}`}
                    </span>
                    {c.state === "running" && (
                      <span>
                        {formatSize(c.copiedBytes)} of {formatSize(c.totalBytes)}
                      </span>
                    )}
                  </div>
                </div>
                {(c.state === "queued" || c.state === "running") && (
                  <div className="download-actions">
                    <button className="btn btn-small" onClick={() => void window.api.cancelCopy(c.id)}>
                      Cancel
                    </button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        </>
      )}

      {removing && (
        <div className="player-overlay" onClick={() => setRemoving(null)}>
          <div className="confirm-dialog" onClick={(e) => e.stopPropagation()}>
            <h3>Remove “{removing.name}”?</h3>
            <label className="checkbox-label">
              <input type="checkbox" checked={deleteFiles} onChange={(e) => setDeleteFiles(e.target.checked)} />
              Also delete the downloaded files
            </label>
            <div className="form-actions">
              <button className="btn btn-danger" onClick={() => void confirmRemove()}>
                Remove
              </button>
              <button className="btn" onClick={() => setRemoving(null)}>
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
