import type { AppSettings, MediaItem } from "../../shared/types.js";

function stripRootPrefix(fullPath: string, root: string): string {
  const normFull = fullPath.replace(/\\/g, "/");
  const normRoot = root.replace(/\\/g, "/").replace(/\/+$/, "");
  return normFull.startsWith(normRoot) ? normFull.slice(normRoot.length + 1) : normFull;
}

/**
 * The folder path segments (directory names only, no filename) an item sits under, relative to
 * the local folder or network share root it was scanned from, prefixed with a top-level grouping
 * segment: "Local" for every local item, or that item's network share's label for a network item
 * — so Library and Network are one browsable tree whose first level is "Local" plus one folder
 * per connected share, instead of two separate sections. When more than one local folder is
 * configured, each local folder's own label is inserted as a second level under "Local" so their
 * relative paths (which are only unique within a single folder) can't collide with one another.
 * Used to group a flat item list into a navigable folder tree without having to persist hierarchy
 * separately — the information is already encoded in each item's `path`.
 */
export function folderSegmentsForItem(item: MediaItem, settings: AppSettings): string[] {
  const source = item.source;
  let rel: string;
  let topSegments: string[];
  if (source.type === "local") {
    const folder = settings.localFolders.find((f) => f.id === source.folderId);
    if (!folder) return [];
    rel = stripRootPrefix(item.path, folder.path);
    topSegments = settings.localFolders.length > 1 ? ["Local", folder.label] : ["Local"];
  } else {
    const share = settings.networkShares.find((s) => s.id === source.shareId);
    if (!share) return [];
    rel = stripRootPrefix(item.path, `smb://${share.host}/${share.share}`);
    topSegments = [share.label];
  }
  const segments = rel.split("/").filter(Boolean);
  return [...topSegments, ...segments.slice(0, -1)];
}

/**
 * The exclusion key ("local:<folderId>:<rel>" / "network:<shareId>:<rel>") for a folder being
 * browsed, or null when [path] is a grouping level (Local, a share's root) rather than a folder
 * inside one.
 */
export function folderKeyForPath(path: string[], settings: AppSettings): string | null {
  if (path.length === 0) return null;
  if (path[0] === "Local") {
    const multi = settings.localFolders.length > 1;
    const folder = multi ? settings.localFolders.find((f) => f.label === path[1]) : settings.localFolders[0];
    const rel = path.slice(multi ? 2 : 1);
    return folder && rel.length > 0 ? `local:${folder.id}:${rel.join("/")}` : null;
  }
  const share = settings.networkShares.find((s) => s.label === path[0]);
  const rel = path.slice(1);
  return share && rel.length > 0 ? `network:${share.id}:${rel.join("/")}` : null;
}

/** The Folders-view path of an exclusion key (the inverse of folderKeyForPath); null if its source is gone. */
export function pathForFolderKey(key: string, settings: AppSettings): string[] | null {
  const [kind, id, ...relParts] = key.split(":");
  const rel = relParts.join(":").split("/").filter(Boolean);
  if (kind === "local") {
    const folder = settings.localFolders.find((f) => f.id === id);
    if (!folder) return null;
    return [...(settings.localFolders.length > 1 ? ["Local", folder.label] : ["Local"]), ...rel];
  }
  const share = settings.networkShares.find((s) => s.id === id);
  return share ? [share.label, ...rel] : null;
}
