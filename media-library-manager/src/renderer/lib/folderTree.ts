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
