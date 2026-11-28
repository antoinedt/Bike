import { BrowserWindow } from "electron";
import { IPC } from "../../shared/ipc.js";
import type { LibraryScanStatus, MediaItem } from "../../shared/types.js";
import { pruneItems, upsertItems } from "../db.js";
import { scanNetworkShare } from "../network/smbClient.js";
import { getSettings } from "../settings.js";
import { scanLocalFolder, type SourceScanResult } from "./scanner.js";

// The library scan: every folder and share at once, in the background, filling the library as
// items are found. Started when the app opens, and by Settings' Scan buttons, a finished copy or
// download — a scan already running for a folder/share is joined rather than started again.
// Items that are gone are only removed once their folder/share has been read in full, so an
// unplugged drive or an unreachable share never empties the library.

type Scope = "local" | "network" | "all";

const running = new Map<string, Promise<SourceScanResult>>();
let pending: MediaItem[] = [];
let flushTimer: NodeJS.Timeout | null = null;
let found = 0;
// Library changes are applied one at a time, so a removal never races an addition.
let dbQueue: Promise<void> = Promise.resolve();

function broadcast(channel: string, payload?: unknown): void {
  for (const win of BrowserWindow.getAllWindows()) win.webContents.send(channel, payload);
}

function serial(change: () => Promise<void>): Promise<void> {
  dbQueue = dbQueue.then(change, change);
  return dbQueue;
}

export function libraryScanStatus(): LibraryScanStatus {
  return { scanning: running.size > 0, found };
}

function sendStatus(): void {
  broadcast(IPC.libraryScanStatus, libraryScanStatus());
}

/** Applies what's been found since the last update and tells the Library to refresh. */
function applyPending(): Promise<void> {
  if (flushTimer) {
    clearTimeout(flushTimer);
    flushTimer = null;
  }
  const batch = pending;
  pending = [];
  if (batch.length === 0) return serial(async () => undefined);
  return serial(() => upsertItems(batch)).then(() => {
    broadcast(IPC.libraryChanged);
    sendStatus();
  });
}

function queueBatch(items: MediaItem[]): void {
  pending.push(...items);
  found += items.length;
  // Several folders report at once; group them into one library update a few times a second.
  if (!flushTimer) flushTimer = setTimeout(() => void applyPending(), 400);
}

function scanSource(
  key: string,
  run: (onBatch: (items: MediaItem[]) => void) => Promise<SourceScanResult>,
  belongs: (item: MediaItem) => boolean
): Promise<SourceScanResult> {
  const existing = running.get(key);
  if (existing) return existing;
  if (running.size === 0) found = 0;
  const job = (async () => {
    try {
      const result = await run(queueBatch);
      await applyPending();
      if (result.complete) await serial(() => pruneItems((item) => belongs(item) && !result.seenIds.has(item.id)));
      return result;
    } finally {
      running.delete(key);
      broadcast(IPC.libraryChanged);
      sendStatus();
    }
  })();
  running.set(key, job);
  sendStatus();
  return job;
}

/**
 * Scans the library folders and/or shares, all at once. Resolves when they've all finished, with
 * a message for each folder/share that couldn't be read (the rest of the library is still updated).
 */
export async function scanLibrary(scope: Scope): Promise<{ errors: string[] }> {
  const { localFolders, networkShares, excludedFolders } = getSettings();
  const excluded = new Set(excludedFolders);
  const errors: string[] = [];
  const jobs: Promise<void>[] = [];
  if (scope !== "network") {
    for (const folder of localFolders) {
      jobs.push(
        scanSource(
          `folder:${folder.id}`,
          (onBatch) => scanLocalFolder(folder, onBatch, excluded),
          (item) => item.source.type === "local" && item.source.folderId === folder.id
        ).then(
          (result) => {
            if (!result.complete) errors.push(`Couldn't read ${folder.label || folder.path}.`);
          },
          (err) => void errors.push(`${folder.label || folder.path}: ${(err as Error).message}`)
        )
      );
    }
  }
  if (scope !== "local") {
    for (const share of networkShares) {
      jobs.push(
        scanSource(
          `share:${share.id}`,
          (onBatch) => scanNetworkShare(share, onBatch, excluded),
          (item) => item.source.type === "network" && item.source.shareId === share.id
        ).then(
          () => undefined,
          (err) => void errors.push(`${share.label || share.host}: ${(err as Error).message}`)
        )
      );
    }
  }
  await Promise.all(jobs);
  // Items from folders/shares that have since been removed in Settings.
  await serial(() =>
    pruneItems((item) =>
      item.source.type === "local"
        ? scope !== "network" && !localFolders.some((f) => f.id === (item.source as { folderId: string }).folderId)
        : scope !== "local" && !networkShares.some((s) => s.id === (item.source as { shareId: string }).shareId)
    )
  );
  broadcast(IPC.libraryChanged);
  return { errors };
}

/** The scan the app runs when it opens: everything, in the background. */
export function scanLibraryInBackground(): void {
  void scanLibrary("all")
    .then(({ errors }) => {
      if (errors.length > 0) console.warn("[library] scan:", errors.join(" "));
    })
    .catch((err) => console.error("[library] scan failed:", err));
}
