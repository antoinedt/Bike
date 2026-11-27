import { app } from "electron";
import { copyFileSync, existsSync } from "node:fs";
import path from "node:path";
import { JSONFilePreset } from "lowdb/node";
import type { Low } from "lowdb";
import type { MediaItem, MediaMetadata } from "../shared/types.js";

const DB_NAME = "media-library.db.json";

/**
 * The library cache lives in the app's data folder (%APPDATA%\media-library-manager on Windows).
 * Older versions saved it relative to wherever the app was started from — usually the install
 * folder, which an update can wipe — so a copy found there is carried over once.
 */
function dbPath(): string {
  const target = path.join(app.getPath("userData"), DB_NAME);
  const legacy = path.resolve(DB_NAME);
  if (!existsSync(target) && legacy !== target && existsSync(legacy)) {
    try {
      copyFileSync(legacy, target);
    } catch {
      // start fresh; a rescan rebuilds it
    }
  }
  return target;
}

interface DbSchema {
  items: MediaItem[];
}

let dbPromise: Promise<Low<DbSchema>> | null = null;

function getDb(): Promise<Low<DbSchema>> {
  if (!dbPromise) {
    dbPromise = JSONFilePreset<DbSchema>(dbPath(), { items: [] });
  }
  return dbPromise;
}

/**
 * A freshly scanned item, carrying forward artworkUrl/title/year/durationSeconds/tags from what
 * the library already had for it (matched by id, which scanners derive from path, so it's stable
 * across rescans) — otherwise every rescan would wipe out everything Fetch artwork / Scan
 * metadata / manual edits had already done.
 */
function carryForward(item: MediaItem, previous: MediaItem): MediaItem {
  return {
    ...item,
    artworkUrl: item.artworkUrl ?? previous.artworkUrl,
    title: item.title ?? previous.title,
    year: item.year ?? previous.year,
    durationSeconds: item.durationSeconds ?? previous.durationSeconds,
    tags: item.tags ?? previous.tags,
    infoFetched: item.infoFetched ?? previous.infoFetched
  };
}

// A background scan adds items many times a second; the in-memory list is what the app reads,
// so writing the file is coalesced to at most once a second.
let writeTimer: NodeJS.Timeout | null = null;
function scheduleWrite(db: Low<DbSchema>): void {
  if (writeTimer) return;
  writeTimer = setTimeout(() => {
    writeTimer = null;
    void db.write().catch((err) => console.error("[db] write failed:", err));
  }, 1000);
}

/** Adds newly scanned items and refreshes ones already in the library (keeping their fetched info). */
export async function upsertItems(items: MediaItem[]): Promise<void> {
  if (items.length === 0) return;
  const db = await getDb();
  const indexById = new Map(db.data.items.map((item, i) => [item.id, i]));
  for (const item of items) {
    const i = indexById.get(item.id);
    if (i === undefined) {
      indexById.set(item.id, db.data.items.length);
      db.data.items.push(item);
    } else {
      db.data.items[i] = carryForward(item, db.data.items[i]);
    }
  }
  scheduleWrite(db);
}

/** Removes items a finished scan didn't find any more (or whose folder/share was removed). */
export async function pruneItems(shouldRemove: (item: MediaItem) => boolean): Promise<void> {
  const db = await getDb();
  const before = db.data.items.length;
  db.data.items = db.data.items.filter((item) => !shouldRemove(item));
  if (db.data.items.length !== before) scheduleWrite(db);
}

export async function getAllItems(): Promise<MediaItem[]> {
  const db = await getDb();
  return db.data.items;
}

/**
 * Patches title/year/durationSeconds/infoFetched onto items by id. Normally artworkUrl/tags only
 * fill in a blank (an existing value, manual or auto-fetched, is left alone) — but with
 * `force: true` (a forced re-fetch, explicitly asked to override whatever was found before) the
 * freshly-fetched artworkUrl wins instead, since the whole point of forcing is to replace a
 * stuck or wrong previous result. Tags are never auto-overwritten, force or not — they're
 * user-assigned, never fetched, so there's nothing of the fetcher's to prefer.
 */
export async function applyMetadata(metadataByItemId: Map<string, MediaMetadata>, force = false): Promise<MediaItem[]> {
  const db = await getDb();
  db.data.items = db.data.items.map((item) => {
    const metadata = metadataByItemId.get(item.id);
    if (!metadata) return item;
    return {
      ...item,
      title: metadata.title ?? item.title,
      year: metadata.year ?? item.year,
      durationSeconds: metadata.durationSeconds ?? item.durationSeconds,
      artworkUrl: force ? metadata.artworkUrl ?? item.artworkUrl : item.artworkUrl ?? metadata.artworkUrl,
      tags: item.tags ?? metadata.tags,
      infoFetched: metadata.infoFetched ?? item.infoFetched
    };
  });
  await db.write();
  return db.data.items;
}

/**
 * Wipes title/year/tags/artworkUrl and resets infoFetched to false for one item — "Clear info" in
 * the details panel, for starting over on an item that got tagged/fetched wrong, or just to make
 * it a fresh candidate for the next "Fetch artwork & info" run instead of a forced one.
 */
export async function clearMetadata(itemId: string): Promise<MediaItem[]> {
  const db = await getDb();
  db.data.items = db.data.items.map((item) => {
    if (item.id !== itemId) return item;
    return { ...item, title: undefined, year: undefined, tags: undefined, artworkUrl: undefined, infoFetched: false };
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
