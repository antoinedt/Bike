import { useState } from "react";
import type { MediaItem } from "../../shared/types.js";
import { KIND_ICON, formatSize } from "../lib/format.js";
import MediaDetailsPanel from "./MediaDetailsPanel.js";

export default function MediaGrid({
  items,
  onOpen
}: {
  items: MediaItem[];
  onOpen: (item: MediaItem) => void;
}) {
  const [selected, setSelected] = useState<MediaItem | null>(null);

  if (items.length === 0) {
    return <div className="empty-state">No media found yet. Try a scan.</div>;
  }

  return (
    <>
      <div className="media-grid">
        {items.map((item) => (
          <button key={item.id} className="media-card" onClick={() => setSelected(item)} title={item.path}>
            {item.artworkUrl ? (
              <img className="media-card-artwork" src={item.artworkUrl} alt="" />
            ) : (
              <div className="media-card-icon">{KIND_ICON[item.kind]}</div>
            )}
            <div className="media-card-name">{item.title || item.name}</div>
            <div className="media-card-meta">
              {item.year ? `${item.year} · ` : ""}
              {item.extension.toUpperCase()} · {formatSize(item.sizeBytes)}
            </div>
          </button>
        ))}
      </div>
      {selected && (
        <MediaDetailsPanel
          item={selected}
          onClose={() => setSelected(null)}
          onOpen={() => {
            onOpen(selected);
            setSelected(null);
          }}
        />
      )}
    </>
  );
}
