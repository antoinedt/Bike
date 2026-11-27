import { mkdir, readdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import type { Mailbox } from "./remoteQueue.js";

/**
 * The mailbox folder in a folder on this PC — typically one this PC shares on the network, which
 * the other devices add as a network share. Same layout as on a share, so they interoperate.
 */
export function localMailbox(folder: string): Mailbox {
  const full = (p: string) => path.join(folder, ...p.split(/[\\/]/).filter(Boolean));
  return {
    list: (dir) => readdir(full(dir)).catch(() => []),
    read: (p) => readFile(full(p)),
    write: async (p, data) => {
      await mkdir(path.dirname(full(p)), { recursive: true });
      await writeFile(full(p), data);
    },
    rename: (from, to) => rename(full(from), full(to)),
    remove: (p) => rm(full(p), { force: true })
  };
}
