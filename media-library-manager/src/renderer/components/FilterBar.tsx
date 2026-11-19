import { useMemo } from "react";
import type { MediaItem } from "../../shared/types.js";

export default function FilterBar({
  items,
  selectedTags,
  onToggleTag,
  dateFrom,
  dateTo,
  onDateFromChange,
  onDateToChange
}: {
  items: MediaItem[];
  selectedTags: string[];
  onToggleTag: (tag: string) => void;
  dateFrom: string;
  dateTo: string;
  onDateFromChange: (value: string) => void;
  onDateToChange: (value: string) => void;
}) {
  const allTags = useMemo(
    () => Array.from(new Set(items.flatMap((i) => i.tags ?? []))).sort((a, b) => a.localeCompare(b)),
    [items]
  );

  return (
    <div className="filter-bar">
      <div className="filter-bar-dates">
        <label className="filter-bar-date-label">
          From
          <input
            type="date"
            className="text-input"
            value={dateFrom}
            onChange={(e) => onDateFromChange(e.target.value)}
          />
        </label>
        <label className="filter-bar-date-label">
          To
          <input type="date" className="text-input" value={dateTo} onChange={(e) => onDateToChange(e.target.value)} />
        </label>
        {(dateFrom || dateTo) && (
          <button
            className="btn btn-small"
            onClick={() => {
              onDateFromChange("");
              onDateToChange("");
            }}
          >
            Clear dates
          </button>
        )}
      </div>
      {allTags.length > 0 && (
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
      )}
    </div>
  );
}
