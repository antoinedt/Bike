import { JSONFilePreset } from "lowdb/node";
import type { Low } from "lowdb";
import type { MediaItem, MediaMetadata } from "../shared/types.js";

interface DbSchema {
  items: MediaItem[];
}

let dbPromise: Promise<Low<DbSchema>> | null = null;

function getDb(): Promise<Low<DbSchema>> {
  if (!dbPromise) {
    dbPromise = JSONFilePreset<DbSchema>("media-library.db.json", { items: [] });
  }
  return dbPromise;
}

/**
 * Replaces all items belonging to a given source (folder/share id) with a fresh scan result,
 * carrying forward artworkUrl/title/year/durationSeconds for files that are still there (matched
 * by id, which scanners derive from path, so it's stable across rescans) — otherwise every
 * rescan would silently wipe out everything Fetch artwork / Scan metadata had already found.
 */
export async function replaceItemsForSource(
  matches: (item: MediaItem) => boolean,
  freshItems: MediaItem[]
): Promise<void> {
  const db = await getDb();
  const previousById = new Map(db.data.items.filter(matches).map((item) => [item.id, item]));
  const merged = freshItems.map((item) => {
    const previous = previousById.get(item.id);
    if (!previous) return item;
    return {
      ...item,
      artworkUrl: item.artworkUrl ?? previous.artworkUrl,
      title: item.title ?? previous.title,
      year: item.year ?? previous.year,
      durationSeconds: item.durationSeconds ?? previous.durationSeconds,
      tags: item.tags ?? previous.tags,
      infoFetched: item.infoFetched ?? previous.infoFetched
    };
  });
  db.data.items = db.data.items.filter((item) => !matches(item)).concat(merged);
  await db.write();
}

export async function getAllItems(): Promise<MediaItem[]> {
  const db = await getDb();
  return db.data.items;
}

/** Patches title/year/durationSeconds/infoFetched (and artworkUrl, if not already set) onto items by id. */
export async function applyMetadata(metadataByItemId: Map<string, MediaMetadata>): Promise<MediaItem[]> {
  const db = await getDb();
  db.data.items = db.data.items.map((item) => {
    const metadata = metadataByItemId.get(item.id);
    if (!metadata) return item;
    return {
      ...item,
      title: metadata.title ?? item.title,
      year: metadata.year ?? item.year,
      durationSeconds: metadata.durationSeconds ?? item.durationSeconds,
      artworkUrl: item.artworkUrl ?? metadata.artworkUrl,
      tags: item.tags ?? metadata.tags,
      infoFetched: metadata.infoFetched ?? item.infoFetched
    };
  });
  await db.write();
  return db.data.items;
}

/**
 * Overwrites title/year/tags/artworkUrl on one item by id with user-provided values (unlike
 * applyMetadata, these take precedence unconditionally — the whole point is letting the user
 * correct a wrong auto-fetched value, or add one by hand after a failed auto fetch, not just fill
 * in blanks). Also marks the item as fetched, so a failed auto-fetch followed by a manual edit
 * doesn't look like "never checked" and get silently re-attempted.
 */
export async function applyManualEdit(
  itemId: string,
  edits: { title?: string; year?: string; tags?: string[]; artworkUrl?: string }
): Promise<MediaItem[]> {
  const db = await getDb();
  db.data.items = db.data.items.map((item) => {
    if (item.id !== itemId) return item;
    return {
      ...item,
      title: edits.title !== undefined ? edits.title || undefined : item.title,
      year: edits.year !== undefined ? edits.year || undefined : item.year,
      tags: edits.tags !== undefined ? edits.tags : item.tags,
      artworkUrl: edits.artworkUrl !== undefined ? edits.artworkUrl || undefined : item.artworkUrl,
      infoFetched: true
    };
  });
  await db.write();
  return db.data.items;
}
