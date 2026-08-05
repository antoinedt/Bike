import type { MediaItem } from "../../shared/types.js";

const KIND_ICON: Record<MediaItem["kind"], string> = {
  video: "🎬",
  audio: "🎵",
  image: "🖼",
  other: "📄"
};

function formatSize(bytes: number): string {
  if (!bytes) return "—";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(value >= 10 || unit === 0 ? 0 : 1)} ${units[unit]}`;
}

export default function MediaGrid({
  items,
  onOpen
}: {
  items: MediaItem[];
  onOpen: (item: MediaItem) => void;
}) {
  if (items.length === 0) {
    return <div className="empty-state">No media found yet. Try a scan.</div>;
  }

  return (
    <div className="media-grid">
      {items.map((item) => (
        <button key={item.id} className="media-card" onClick={() => onOpen(item)} title={item.path}>
          <div className="media-card-icon">{KIND_ICON[item.kind]}</div>
          <div className="media-card-name">{item.name}</div>
          <div className="media-card-meta">
            {item.extension.toUpperCase()} · {formatSize(item.sizeBytes)}
          </div>
        </button>
      ))}
    </div>
  );
}
