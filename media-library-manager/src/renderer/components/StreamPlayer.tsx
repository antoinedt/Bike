import type { MediaItem } from "../../shared/types.js";

export default function StreamPlayer({
  item,
  url,
  onClose
}: {
  item: MediaItem;
  url: string;
  onClose: () => void;
}) {
  return (
    <div className="player-overlay" onClick={onClose}>
      <div className="player-panel" onClick={(e) => e.stopPropagation()}>
        <div className="player-header">
          <span className="player-title">{item.name}</span>
          <button className="btn btn-small" onClick={onClose}>
            Close
          </button>
        </div>
        {item.kind === "audio" ? (
          <audio className="player-media" src={url} controls autoPlay />
        ) : (
          <video className="player-media" src={url} controls autoPlay />
        )}
      </div>
    </div>
  );
}
