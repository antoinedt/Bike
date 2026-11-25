import SMB2 from "smb2";
import { createRequire } from "node:module";
import type { NetworkShare } from "../../shared/types.js";
import { portOf, requestRaw, toSmbPath, uncPath } from "../network/smbRawClient.js";

// Streamed writes to an SMB share. The smb2 package's own writeFile needs the whole file in memory
// (a no-go for multi-GB videos), so this drives its CREATE / WRITE / CLOSE requests directly, the
// same way smbRawClient does for reads.

const require = createRequire(import.meta.url);
// eslint-disable-next-line @typescript-eslint/no-var-requires
const BigInt64 = require("smb2/lib/tools/bigint.js");

/** The largest WRITE the smb2 package itself sends (64 KB minus its header overhead). */
const MAX_WRITE = 0x00010000 - 0x71;
/** WRITE requests kept in flight at once, so throughput isn't capped by round-trip time. */
const WRITES_IN_FLIGHT = 8;

export function connectShare(share: NetworkShare): SMB2 {
  return new SMB2({
    share: uncPath(share),
    domain: share.domain || "WORKGROUP",
    username: share.username,
    password: share.password,
    port: portOf(share),
    autoCloseTimeout: 0
  });
}

/** A path inside the share for something at `relPath` under the share's configured sub-path. */
export function sharePath(share: NetworkShare, relPath: string): string {
  return toSmbPath([share.subPath, relPath].map((p) => p.replace(/^[\\/]+|[\\/]+$/g, "")).filter(Boolean).join("/"));
}

function call<T>(fn: (cb: (err: Error | null, result: T) => void) => void): Promise<T> {
  return new Promise((resolve, reject) => fn((err, result) => (err ? reject(err) : resolve(result))));
}

export async function smbExists(client: SMB2, path: string): Promise<boolean> {
  return call<boolean>((cb) => client.exists(path, cb));
}

/** The smb2 package's exists() only finds files (it opens them as files), so folders are probed by listing them. */
async function smbDirExists(client: SMB2, path: string): Promise<boolean> {
  try {
    await call<string[]>((cb) => client.readdir(path, cb));
    return true;
  } catch {
    return false;
  }
}

export async function smbMkdirp(client: SMB2, path: string): Promise<void> {
  let current = "";
  for (const part of path.split("\\").filter(Boolean)) {
    current = current ? `${current}\\${part}` : part;
    if (!(await smbDirExists(client, current))) {
      await call<void>((cb) => client.mkdir(current, (err) => cb(err, undefined)));
    }
  }
}

/** Folder names directly inside `path` (the smb2 client can't tell files from folders, so each name is probed). */
export async function smbListFolders(client: SMB2, path: string): Promise<string[]> {
  const names = await call<string[]>((cb) => client.readdir(path, cb));
  const folders: string[] = [];
  for (const name of names) {
    if (name.startsWith(".")) continue;
    const child = path ? `${path}\\${name}` : name;
    try {
      await call<string[]>((cb) => client.readdir(child, cb));
      folders.push(name);
    } catch {
      // a file
    }
  }
  return folders.sort((a, b) => a.localeCompare(b, undefined, { sensitivity: "base" }));
}

export async function smbDelete(client: SMB2, path: string): Promise<void> {
  await call<void>((cb) => client.unlink(path, (err) => cb(err, undefined)));
}

/** Creates (never overwrites) `name` in `dir`, or "name (2).ext" etc. if taken. */
export async function smbCreateUnique(client: SMB2, dir: string, name: string, numbered: (n: number) => string): Promise<{ name: string; path: string; fileId: Buffer }> {
  for (let n = 1; n < 1000; n++) {
    const candidate = numbered(n);
    const path = dir ? `${dir}\\${candidate}` : candidate;
    if ((await smbExists(client, path)) || (await smbDirExists(client, path))) continue;
    const file = await requestRaw<{ FileId: Buffer }>(client, "create", { path });
    return { name: candidate, path, fileId: file.FileId };
  }
  throw new Error(`Too many files called "${name}" already.`);
}

/** Writes everything from `source` to an open file, several WRITE requests at a time. */
export async function smbWriteAll(
  client: SMB2,
  fileId: Buffer,
  source: AsyncIterable<Buffer>,
  onProgress: (written: number) => void,
  isCancelled: () => boolean
): Promise<number> {
  let offset = 0;
  let written = 0;
  const inFlight = new Set<Promise<void>>();
  let failure: Error | null = null;

  const send = (chunk: Buffer, at: number) => {
    const p = requestRaw(client, "write", { FileId: fileId, Offset: new BigInt64(8, at).toBuffer(), Buffer: chunk })
      .then(() => {
        written += chunk.length;
        onProgress(written);
      })
      .catch((err: Error) => {
        failure = failure ?? err;
      })
      .finally(() => inFlight.delete(p));
    inFlight.add(p);
  };

  for await (const data of source) {
    for (let i = 0; i < data.length; i += MAX_WRITE) {
      if (isCancelled()) throw new Error("Cancelled.");
      if (failure) throw failure;
      while (inFlight.size >= WRITES_IN_FLIGHT) await Promise.race(inFlight);
      const chunk = data.subarray(i, Math.min(i + MAX_WRITE, data.length));
      send(Buffer.from(chunk), offset);
      offset += chunk.length;
    }
  }
  await Promise.all(inFlight);
  if (failure) throw failure;
  return written;
}

export async function smbCloseFile(client: SMB2, fileId: Buffer): Promise<void> {
  await requestRaw(client, "close", { FileId: fileId });
}
