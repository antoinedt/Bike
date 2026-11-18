import type { AppSettings, MediaItem } from "../../shared/types.js";

function stripRootPrefix(fullPath: string, root: string): string {
  const normFull = fullPath.replace(/\\/g, "/");
  const normRoot = root.replace(/\\/g, "/").replace(/\/+$/, "");
  return normFull.startsWith(normRoot) ? normFull.slice(normRoot.length + 1) : normFull;
}

/**
 * The folder path segments (directory names only, no filename) an item sits under, relative to
 * the local folder or network share root it was scanned from. Used to group a flat item list
 * into a navigable folder tree without having to persist hierarchy separately — the information
 * is already encoded in each item's `path`.
 */
export function folderSegmentsForItem(item: MediaItem, settings: AppSettings): string[] {
  const source = item.source;
  let rel: string;
  if (source.type === "local") {
    const folder = settings.localFolders.find((f) => f.id === source.folderId);
    if (!folder) return [];
    rel = stripRootPrefix(item.path, folder.path);
  } else {
    const share = settings.networkShares.find((s) => s.id === source.shareId);
    if (!share) return [];
    rel = stripRootPrefix(item.path, `smb://${share.host}/${share.share}`);
  }
  const segments = rel.split("/").filter(Boolean);
  return segments.slice(0, -1);
}
