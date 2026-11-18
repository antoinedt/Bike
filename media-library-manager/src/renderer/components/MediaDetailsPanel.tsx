import type { MediaItem } from "../../shared/types.js";
import { KIND_ICON, formatDuration, formatSize } from "../lib/format.js";

export default function MediaDetailsPanel({
  item,
  onOpen,
  onClose
}: {
  item: MediaItem;
  onOpen: () => void;
  onClose: () => void;
}) {
  return (
    <div className="player-overlay" onClick={onClose}>
      <div className="details-panel" onClick={(e) => e.stopPropagation()}>
        <div className="details-header">
          <span className="player-title">{item.title || item.name}</span>
          <button className="btn btn-small" onClick={onClose}>
            Close
          </button>
        </div>
        <div className="details-body">
          {item.artworkUrl ? (
            <img className="details-artwork" src={item.artworkUrl} alt="" />
          ) : (
            <div className="details-artwork-placeholder">{KIND_ICON[item.kind]}</div>
          )}
          <dl className="details-meta">
            <dt>Year</dt>
            <dd>{item.year || "—"}</dd>
            <dt>Length</dt>
            <dd>{formatDuration(item.durationSeconds)}</dd>
            <dt>Size</dt>
            <dd>{formatSize(item.sizeBytes)}</dd>
            <dt>Format</dt>
            <dd>{item.extension.toUpperCase()}</dd>
          </dl>
        </div>
        <button className="btn btn-primary" onClick={onOpen}>
          {item.source.type === "network" ? "Play" : "Open"}
        </button>
      </div>
    </div>
  );
}
