import Store from "electron-store";
import type { MediaItem } from "../shared/types.js";
import { getAllItems } from "./db.js";
import { getSettings } from "./settings.js";
import { readSidecar, writeResumeToSidecar } from "./metadata/metadataScanner.js";

/**
 * "Continue where you left off", shared between devices: the position goes into the
 * "<file>.metadata.json" sidecar next to the file (so the phone, TV or another PC browsing the
 * same share picks it up), and into a local store as a fallback when that file can't be written
 * (read-only share). Whichever was updated last wins. Not kept for the first 30 s or the last 5%.
 */
interface Saved {
  position: number;
  at: number;
}

const store = new Store<{ positions: Record<string, Saved> }>({ name: "resume-positions", defaults: { positions: {} } });

async function itemById(itemId: string): Promise<MediaItem | undefined> {
  return (await getAllItems()).find((i) => i.id === itemId);
}

export async function getResume(itemId: string): Promise<number | null> {
  const local = store.get("positions")[itemId];
  const item = await itemById(itemId);
  const sidecar = item ? await readSidecar(item, getSettings().networkShares).catch(() => null) : null;
  const shared: Saved | null =
    sidecar?.resumePositionMs ? { position: sidecar.resumePositionMs, at: sidecar.resumeUpdatedAt ?? 0 } : null;
  const best = [local, shared].filter((x): x is Saved => !!x).sort((a, b) => b.at - a.at)[0];
  return best ? best.position : null;
}

export async function saveResume(itemId: string, positionMs: number, durationMs: number, ended: boolean): Promise<void> {
  const keep = !ended && positionMs >= 30_000 && !(durationMs > 0 && positionMs >= durationMs * 0.95);
  const positions = { ...store.get("positions") };
  const now = Date.now();
  if (keep) positions[itemId] = { position: Math.floor(positionMs), at: now };
  else delete positions[itemId];
  store.set("positions", positions);
  const item = await itemById(itemId);
  if (item) {
    await writeResumeToSidecar(item, keep ? Math.floor(positionMs) : null, now, getSettings().networkShares).catch(() => {
      // Read-only share or offline: the local copy above still works on this PC.
    });
  }
}
