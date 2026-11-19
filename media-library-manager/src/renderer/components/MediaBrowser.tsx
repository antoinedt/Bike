import { useMemo, useState } from "react";
import type { AppSettings, MediaItem } from "../../shared/types.js";
import { folderSegmentsForItem } from "../lib/folderTree.js";
import MediaGrid from "./MediaGrid.js";

/**
 * Groups a flat item list into a navigable folder tree (derived from each item's path, relative
 * to its local folder / network share root — no separate hierarchy storage needed) when `filter`
 * is empty. A non-empty filter switches to a flat, recursive name search across every folder
 * instead, since "search the whole library" and "browse one folder" are different intents.
 */
export default function MediaBrowser({
  items,
  settings,
  filter,
  onOpen,
  onDownload
}: {
  items: MediaItem[];
  settings: AppSettings;
  filter: string;
  onOpen: (item: MediaItem) => void;
  onDownload?: (item: MediaItem) => void;
}) {
  const [path, setPath] = useState<string[]>([]);

  const withSegments = useMemo(
    () => items.map((item) => ({ item, segments: folderSegmentsForItem(item, settings) })),
    [items, settings]
  );

  if (filter.trim()) {
    const q = filter.toLowerCase();
    const matched = withSegments.filter(({ item }) => item.name.toLowerCase().includes(q)).map((x) => x.item);
    return <MediaGrid items={matched} onOpen={onOpen} onDownload={onDownload} />;
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
          <button className="btn btn-small" onClick={() => setPath([])} disabled={depth === 0}>
            Root
          </button>
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
        <MediaGrid items={filesHere} onOpen={onOpen} onDownload={onDownload} />
      )}
    </div>
  );
}
