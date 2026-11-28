import { IconButton } from "./Icon.js";
import { useMemo, useState } from "react";
import type { AppSettings, MediaItem } from "../../shared/types.js";
import { folderKeyForPath, folderSegmentsForItem, pathForFolderKey } from "../lib/folderTree.js";
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
  onItemUpdated,
  onSettingsChanged
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
  /** Called after a folder is excluded / included again, so the page reloads its settings. */
  onSettingsChanged?: () => Promise<void> | void;
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
  // Excluded folders have no scanned files, so they only appear (greyed) when Settings says to
  // show them — that's how one gets included again.
  const excludedHere = new Set<string>();
  if (settings.showExcludedFolders) {
    for (const key of settings.excludedFolders ?? []) {
      const p = pathForFolderKey(key, settings);
      if (p && p.length > depth && path.every((seg, i) => p[i] === seg)) {
        subfolders.add(p[depth]);
        if (p.length === depth + 1) excludedHere.add(p[depth]);
      }
    }
  }
  const sortedFolders = Array.from(subfolders).sort((a, b) => a.localeCompare(b));
  const currentKey = folderKeyForPath(path, settings);
  const excludedKeys = new Set(settings.excludedFolders ?? []);
  // This folder, or the excluded folder above it.
  const excludedAncestor = path
    .map((_, i) => folderKeyForPath(path.slice(0, i + 1), settings))
    .find((k) => k && excludedKeys.has(k) && k !== currentKey);
  const currentExcluded = !!currentKey && excludedKeys.has(currentKey);

  async function toggleExcluded(exclude: boolean) {
    if (!currentKey) return;
    await window.api.setFolderExcluded(currentKey, exclude);
    await onSettingsChanged?.();
    if (exclude && !settings.showExcludedFolders) setPath(path.slice(0, -1));
  }

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
      {currentKey && (
        <label className="checkbox-label exclude-toggle" title="Leave this folder and everything in it out of the library">
          <input
            type="checkbox"
            checked={currentExcluded || !!excludedAncestor}
            disabled={!!excludedAncestor}
            onChange={(e) => void toggleExcluded(e.target.checked)}
          />
          {excludedAncestor ? "Excluded with its parent folder" : "Exclude this folder and its subfolders from scans"}
        </label>
      )}
      {sortedFolders.length > 0 && (
        <div className="folder-row">
          {sortedFolders.map((name) => (
            <button
              key={name}
              className={`folder-card${excludedHere.has(name) ? " excluded" : ""}`}
              onClick={() => setPath([...path, name])}
              title={excludedHere.has(name) ? "Excluded from scans" : undefined}
            >
              {excludedHere.has(name) ? "🚫" : "📁"} {name}
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
