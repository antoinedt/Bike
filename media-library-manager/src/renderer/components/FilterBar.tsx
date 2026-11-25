import { useMemo } from "react";
import type { MediaItem } from "../../shared/types.js";

/** Tag chips: click to toggle; several selected tags match an item with ANY of them. */
export default function FilterBar({
  items,
  selectedTags,
  onToggleTag
}: {
  items: MediaItem[];
  selectedTags: string[];
  onToggleTag: (tag: string) => void;
}) {
  const allTags = useMemo(
    () => Array.from(new Set(items.flatMap((i) => i.tags ?? []))).sort((a, b) => a.localeCompare(b)),
    [items]
  );

  if (allTags.length === 0) return null;
  return (
    <div className="filter-bar">
      <div className="filter-bar-tags">
        {allTags.map((tag) => (
          <button
            key={tag}
            className={selectedTags.includes(tag) ? "tag-filter-chip tag-filter-chip-active" : "tag-filter-chip"}
            onClick={() => onToggleTag(tag)}
          >
            {tag}
          </button>
        ))}
      </div>
    </div>
  );
}
