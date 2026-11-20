import { useMemo, useState } from "react";
import type { AppSettings, MediaItem } from "../../shared/types.js";
import { folderSegmentsForItem } from "../lib/folderTree.js";
import MediaGrid from "./MediaGrid.js";

/**
 * Groups a flat item list into a navigable folder tree (derived from each item's path, relative
 * to its local folder / network share root — no separate hierarchy storage needed) when no
 * filter is active. Any active filter (name text, tags, or a date range) switches to a flat
 * search across every folder instead, since "search/filter the whole library" and "browse one
 * folder" are different intents. All active filter kinds combine with AND; selecting several
 * tags matches an item with ANY of them.
 */
export default function MediaBrowser({
  items,
  settings,
  filter,
  tagFilter = [],
  dateFrom,
  dateTo,
  onOpen,
  onDownload,
  onItemUpdated,
  onItemsChanged
}: {
  items: MediaItem[];
  settings: AppSettings;
  filter: string;
  tagFilter?: string[];
  dateFrom?: number | null;
  dateTo?: number | null;
  onOpen: (item: MediaItem) => void;
  onDownload?: (item: MediaItem) => void;
  onItemUpdated: (item: MediaItem) => void;
  /** Called with the full, freshly-refreshed item list after a "Fetch artwork & info" run. */
  onItemsChanged: (items: MediaItem[]) => void;
}) {
  const [path, setPath] = useState<string[]>([]);
  const [includeSubfolders, setIncludeSubfolders] = useState(false);
  const [forceRefetch, setForceRefetch] = useState(false);
  const [fetchingInfo, setFetchingInfo] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);

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
  const hasActiveFilter = nameQuery.length > 0 || tagFilter.length > 0 || dateFrom != null || dateTo != null;

  function matches(item: MediaItem): boolean {
    if (nameQuery && !item.name.toLowerCase().includes(nameQuery)) return false;
    if (tagFilter.length > 0 && !(item.tags ?? []).some((t) => tagFilter.includes(t))) return false;
    if (dateFrom != null && item.modifiedAt < dateFrom) return false;
    if (dateTo != null && item.modifiedAt > dateTo) return false;
    return true;
  }

  async function fetchInfo(scope: MediaItem[]) {
    setFetchingInfo(true);
    setFetchError(null);
    try {
      const updated = await window.api.scanMetadata(scope.map((i) => i.id), forceRefetch);
      onItemsChanged(updated);
    } catch (err) {
      setFetchError((err as Error).message);
    } finally {
      setFetchingInfo(false);
    }
  }

  const fetchButton = (scope: MediaItem[], showSubfolderToggle: boolean) => (
    <div className="page-actions">
      {showSubfolderToggle && (
        <label className="checkbox-label">
          <input
            type="checkbox"
            checked={includeSubfolders}
            onChange={(e) => setIncludeSubfolders(e.target.checked)}
          />
          Include subfolders
        </label>
      )}
      <label className="checkbox-label" title="Re-fetch everything in scope, overriding whatever was found before.">
        <input type="checkbox" checked={forceRefetch} onChange={(e) => setForceRefetch(e.target.checked)} />
        Force
      </label>
      <button className="btn" onClick={() => void fetchInfo(scope)} disabled={fetchingInfo || scope.length === 0}>
        {fetchingInfo ? "Fetching artwork & info…" : "Fetch artwork & info"}
      </button>
    </div>
  );

  if (hasActiveFilter) {
    const matched = withSegments.filter(({ item }) => matches(item)).map((x) => x.item);
    return (
      <div>
        {fetchError && <p className="error">{fetchError}</p>}
        {fetchButton(matched, false)}
        <MediaGrid items={matched} allTags={allTags} onOpen={onOpen} onDownload={onDownload} onItemUpdated={onItemUpdated} />
      </div>
    );
  }

  const depth = path.length;
  const subfolders = new Set<string>();
  const filesHere: MediaItem[] = [];
  const filesHereAndBelow: MediaItem[] = [];
  for (const { item, segments } of withSegments) {
    if (segments.length < depth) continue;
    if (!path.every((seg, i) => segments[i] === seg)) continue;
    filesHereAndBelow.push(item);
    if (segments.length === depth) {
      filesHere.push(item);
    } else {
      subfolders.add(segments[depth]);
    }
  }
  const sortedFolders = Array.from(subfolders).sort((a, b) => a.localeCompare(b));
  const scope = includeSubfolders ? filesHereAndBelow : filesHere;

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
      {fetchError && <p className="error">{fetchError}</p>}
      {fetchButton(scope, sortedFolders.length > 0)}
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
          onItemUpdated={onItemUpdated}
        />
      )}
    </div>
  );
}
