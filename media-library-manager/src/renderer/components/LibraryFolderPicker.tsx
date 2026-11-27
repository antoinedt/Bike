import { useMemo, useState } from "react";
import type { AppSettings, MediaItem } from "../../shared/types.js";
import { folderSegmentsForItem } from "../lib/folderTree.js";
import { IconButton } from "./Icon.js";

/** Items under `path` (a folder of the library tree): only those directly in it, or also below. */
export function itemsInFolder(items: MediaItem[], settings: AppSettings, path: string[], includeSubfolders: boolean): MediaItem[] {
  return items.filter((item) => {
    const segments = folderSegmentsForItem(item, settings);
    if (segments.length < path.length) return false;
    if (!path.every((seg, i) => segments[i] === seg)) return false;
    return includeSubfolders || segments.length === path.length;
  });
}

/**
 * Browse the library's folder tree (Local + each network share) and pick one folder. Starts at
 * `initialPath`; "Use this folder" picks whichever folder is open, so the top level means the
 * whole library.
 */
export default function LibraryFolderPicker({
  items,
  settings,
  initialPath,
  onPick,
  onClose
}: {
  items: MediaItem[];
  settings: AppSettings;
  initialPath: string[];
  onPick: (path: string[]) => void;
  onClose: () => void;
}) {
  const [path, setPath] = useState<string[]>(initialPath);
  const segmentsList = useMemo(() => items.map((item) => folderSegmentsForItem(item, settings)), [items, settings]);

  const subfolders = new Set<string>();
  let filesBelow = 0;
  for (const segments of segmentsList) {
    if (segments.length < path.length) continue;
    if (!path.every((seg, i) => segments[i] === seg)) continue;
    filesBelow++;
    if (segments.length > path.length) subfolders.add(segments[path.length]);
  }
  const sorted = Array.from(subfolders).sort((a, b) => a.localeCompare(b));

  return (
    <div className="player-overlay" onClick={onClose}>
      <div className="details-panel folder-picker" onClick={(e) => e.stopPropagation()}>
        <div className="details-header">
          <span className="player-title">Choose a folder</span>
        </div>
        <div className="breadcrumbs">
          <IconButton icon="home" label="Whole library" className="btn-small" onClick={() => setPath([])} disabled={path.length === 0} />
          {path.length === 0 && <span> Whole library</span>}
          {path.map((seg, i) => (
            <span key={i}>
              {" / "}
              <button className="btn btn-small" onClick={() => setPath(path.slice(0, i + 1))} disabled={i === path.length - 1}>
                {seg}
              </button>
            </span>
          ))}
        </div>
        <div className="folder-picker-list">
          {sorted.length === 0 && <p className="hint">No subfolders here.</p>}
          {sorted.map((name) => (
            <button key={name} className="folder-card" onClick={() => setPath([...path, name])}>
              📁 {name}
            </button>
          ))}
        </div>
        <p className="hint">
          {filesBelow} file{filesBelow === 1 ? "" : "s"} in this folder and its subfolders
        </p>
        <div className="form-actions">
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" onClick={() => onPick(path)}>
            Use this folder
          </button>
        </div>
      </div>
    </div>
  );
}
