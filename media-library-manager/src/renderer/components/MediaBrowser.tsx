import { IconButton } from "./Icon.js";
import { useMemo, useState } from "react";
import type { AppSettings, MediaItem } from "../../shared/types.js";
import { folderSegmentsForItem } from "../lib/folderTree.js";
import MediaGrid from "./MediaGrid.js";

/**
 * Groups a flat item list into a navigable folder tree (derived from each item's path, relative
 * to its local folder / network share root — no separate hierarchy storage needed) when no
 * filter is active. Any active filter (name text or tags) switches to a flat
 * search across every folder instead, since "search/filter the whole library" and "browse one
 * folder" are different intents. All active filter kinds combine with AND; selecting several
 * tags matches an item with ANY of them.
 */
export default function MediaBrowser({
  items,
  settings,
  filter,
  tagFilter = [],
  onOpen,
  onDownload,
  onCopy,
  onCast,
  onItemUpdated
}: {
  items: MediaItem[];
  settings: AppSettings;
  filter: string;
  tagFilter?: string[];
  onOpen: (item: MediaItem) => void;
  onDownload?: (item: MediaItem) => void;
  onCopy?: (item: MediaItem) => void;
  onCast?: (item: MediaItem) => void;
  onItemUpdated: (item: MediaItem) => void;
}) {
  const [path, setPath] = useState<string[]>([]);

  const allTags = useMemo(() => {
    const set = new Set<string>();
    for (const item of items) {
      for (const tag of item.tags ?? []) set.add(tag);
    }
    return Array.from(set).sort((a, b) => a.localeCompare(b));
  }, [items]);

  const withSegments = useMemo(
    () => items.map((item) => ({ item, segments: folderSegmentsForItem(item, settings) })),
    [items, settings]
  );

  const nameQuery = filter.trim().toLowerCase();
  const hasActiveFilter = nameQuery.length > 0 || tagFilter.length > 0;

  function matches(item: MediaItem): boolean {
    if (nameQuery && !item.name.toLowerCase().includes(nameQuery)) return false;
    if (tagFilter.length > 0 && !(item.tags ?? []).some((t) => tagFilter.includes(t))) return false;
    return true;
  }

  if (hasActiveFilter) {
    const matched = withSegments.filter(({ item }) => matches(item)).map((x) => x.item);
    return (
      <div>
        <MediaGrid items={matched} allTags={allTags} onOpen={onOpen} onDownload={onDownload} onCopy={onCopy} onCast={onCast} onItemUpdated={onItemUpdated} />
      </div>
    );
  }

  const depth = path.length;
  const subfolders = new Set<string>();
  const filesHere: MediaItem[] = [];
  for (const { item, segments } of withSegments) {
    if (segments.length < depth) continue;
    if (!path.every((seg, i) => segments[i] === seg)) continue;
    if (segments.length === depth) {
      filesHere.push(item);
    } else {
      subfolders.add(segments[depth]);
    }
  }
  const sortedFolders = Array.from(subfolders).sort((a, b) => a.localeCompare(b));

  return (
    <div>
      {(depth > 0 || sortedFolders.length > 0) && (
        <div className="breadcrumbs">
          <IconButton icon="home" label="Top folder" className="btn-small" onClick={() => setPath([])} disabled={depth === 0} />
          {path.map((seg, i) => (
            <span key={i}>
              {" / "}
              <button className="btn btn-small" onClick={() => setPath(path.slice(0, i + 1))} disabled={i === depth - 1}>
                {seg}
              </button>
            </span>
          ))}
        </div>
      )}
      {sortedFolders.length > 0 && (
        <div className="folder-row">
          {sortedFolders.map((name) => (
            <button key={name} className="folder-card" onClick={() => setPath([...path, name])}>
              📁 {name}
            </button>
          ))}
        </div>
      )}
      {(filesHere.length > 0 || sortedFolders.length === 0) && (
        <MediaGrid
          items={filesHere}
          allTags={allTags}
          onOpen={onOpen}
          onDownload={onDownload}
          onCopy={onCopy}
          onCast={onCast}
          onItemUpdated={onItemUpdated}
        />
      )}
    </div>
  );
}
