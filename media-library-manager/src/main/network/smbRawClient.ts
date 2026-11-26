import { createWriteStream } from "node:fs";
import { createRequire } from "node:module";
import SMB2 from "smb2";
import type { NetworkShare } from "../../shared/types.js";
import { applySmb2Fixes } from "./smb2Fixes.js";

// Every SMB connection in the app goes through the smb2 package: NTLMv2 sign-in, STATUS_PENDING handling.
applySmb2Fixes();

/**
 * The public smb2 API only offers whole-file readFile/writeFile, which is useless for
 * streaming playback (it buffers the entire file in memory and blocks until fully fetched).
 * The SMB2 READ command it builds on top of does support arbitrary offset/length, so we reach
 * into the package's internal request plumbing (undocumented, but stable across 0.2.x) to issue
 * ranged reads directly. require() (not a static import) because these deep paths aren't part
 * of the package's public entry point.
 */
const require = createRequire(import.meta.url);
// eslint-disable-next-line @typescript-eslint/no-var-requires
const SMB2Connection = require("smb2/lib/tools/smb2-connection.js");
// eslint-disable-next-line @typescript-eslint/no-var-requires
const SMB2Forge = require("smb2/lib/tools/smb2-forge.js");
// eslint-disable-next-line @typescript-eslint/no-var-requires
const BigInt64 = require("smb2/lib/tools/bigint.js");

/** Single SMB2 READ requests are capped well under typical server-negotiated limits. */
const MAX_SMB_READ_CHUNK = 0x00010000;

export interface RawFileHandle {
  client: SMB2;
  fileId: Buffer;
  size: number;
}

/** The host without an optional ":port" (e.g. "nas.local:1445"). */
function hostOnly(share: NetworkShare): string {
  return share.host.replace(/:\d+$/, "");
}

/** The port from a "host:port" host, if one was given (445 otherwise). */
export function portOf(share: NetworkShare): number | undefined {
  const match = /:(\d+)$/.exec(share.host);
  return match ? Number(match[1]) : undefined;
}

export function uncPath(share: NetworkShare): string {
  return `\\\\${hostOnly(share)}\\${share.share}`;
}

export function toSmbPath(relPath: string): string {
  return relPath.replace(/\//g, "\\");
}

/**
 * The EndofFile field from the CREATE response is an 8-byte little-endian integer. The
 * library's own readfile.js parses it with `|=`/`<<`, which silently truncates at 32 bits
 * (breaks on files >4GB) — this reimplementation avoids that.
 */
function fileSizeFromEndOfFile(buf: Buffer): number {
  let size = 0;
  for (let i = 0; i < buf.length; i++) {
    size += buf[i] * 2 ** (i * 8);
  }
  return size;
}

export function requestRaw<T>(client: SMB2, command: string, params: Record<string, unknown>): Promise<T> {
  return new Promise((resolve, reject) => {
    const wrapped = SMB2Connection.requireConnect(function (this: unknown, cb: (err: Error | null, result: T) => void) {
      SMB2Forge.request(command, params, this, cb);
    });
    wrapped.call(client, (err: Error | null, result: T) => (err ? reject(err) : resolve(result)));
  });
}

/** Opens a file handle on the share and reports its real size (from the SMB server, not readdir). */
export async function openRawFile(share: NetworkShare, relPath: string): Promise<RawFileHandle> {
  const client = new SMB2({
    share: uncPath(share),
    domain: share.domain || "WORKGROUP",
    username: share.username,
    password: share.password,
    port: portOf(share),
    // A streaming session can sit idle between reads (paused video); we manage its lifetime
    // explicitly via closeRawFile rather than letting the library's idle timer sever it.
    autoCloseTimeout: 0
  });
  const file = await requestRaw<{ FileId: Buffer; EndofFile: Buffer }>(client, "open", {
    path: toSmbPath(relPath)
  });
  return { client, fileId: file.FileId, size: fileSizeFromEndOfFile(file.EndofFile) };
}

/**
 * READ requests kept in flight at once. One at a time, speed is capped at 64 KB per network round
 * trip (~17 Mbit/s at 30 ms, e.g. over ZeroTier); 16 in flight keeps ~1 MB on the way.
 */
const READS_IN_FLIGHT = 16;

function readChunk(handle: RawFileHandle, pos: number, length: number): Promise<Buffer> {
  return requestRaw<Buffer>(handle.client, "read", {
    FileId: handle.fileId,
    Length: length,
    Offset: new BigInt64(8, pos).toBuffer()
  });
}

/**
 * Reads bytes `start`..`end` (inclusive) in order, with up to READS_IN_FLIGHT READ requests
 * outstanding, yielding each 64 KB piece as soon as it (and everything before it) has arrived.
 */
export async function* readRawStream(handle: RawFileHandle, start: number, end: number): AsyncGenerator<Buffer> {
  const last = Math.min(end, handle.size - 1);
  let next = start;
  const pending: Promise<Buffer>[] = [];
  const fill = () => {
    while (pending.length < READS_IN_FLIGHT && next <= last) {
      const length = Math.min(MAX_SMB_READ_CHUNK, last - next + 1);
      const p = readChunk(handle, next, length);
      p.catch(() => undefined); // surfaced when awaited below
      pending.push(p);
      next += length;
    }
  };
  fill();
  while (pending.length > 0) {
    const data = await pending.shift()!;
    if (data.length === 0) break;
    fill();
    yield data;
  }
}

/** Reads an arbitrary byte range (in parallel pieces, see readRawStream). */
export async function readRawRange(handle: RawFileHandle, offset: number, length: number): Promise<Buffer> {
  const chunks: Buffer[] = [];
  for await (const chunk of readRawStream(handle, offset, offset + length - 1)) chunks.push(chunk);
  return Buffer.concat(chunks);
}

/** Saves a full local copy of a share file to `destPath` — distinct from streaming, which never writes to disk. */
export async function downloadRawFile(
  share: NetworkShare,
  relPath: string,
  destPath: string,
  onProgress?: (bytesWritten: number, totalBytes: number) => void
): Promise<void> {
  const handle = await openRawFile(share, relPath);
  const out = createWriteStream(destPath);
  try {
    let pos = 0;
    for await (const data of readRawStream(handle, 0, handle.size - 1)) {
      pos += data.length;
      onProgress?.(pos, handle.size);
      if (!out.write(data)) {
        await new Promise<void>((resolve) => out.once("drain", () => resolve()));
      }
    }
  } finally {
    await new Promise<void>((resolve) => out.end(() => resolve()));
    await closeRawFile(handle);
  }
}

export async function closeRawFile(handle: RawFileHandle): Promise<void> {
  try {
    await requestRaw(handle.client, "close", { FileId: handle.fileId });
  } catch {
    // best-effort: fall through to tearing down the socket regardless
  } finally {
    handle.client.close();
  }
}
