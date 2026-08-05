import { createRequire } from "node:module";
import SMB2 from "smb2";
import type { NetworkShare } from "../../shared/types.js";

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

function uncPath(share: NetworkShare): string {
  return `\\\\${share.host}\\${share.share}`;
}

function toSmbPath(relPath: string): string {
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

function requestRaw<T>(client: SMB2, command: string, params: Record<string, unknown>): Promise<T> {
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
    // A streaming session can sit idle between reads (paused video); we manage its lifetime
    // explicitly via closeRawFile rather than letting the library's idle timer sever it.
    autoCloseTimeout: 0
  });
  const file = await requestRaw<{ FileId: Buffer; EndofFile: Buffer }>(client, "open", {
    path: toSmbPath(relPath)
  });
  return { client, fileId: file.FileId, size: fileSizeFromEndOfFile(file.EndofFile) };
}

/** Reads an arbitrary byte range, transparently split into SMB2-sized READ requests. */
export async function readRawRange(handle: RawFileHandle, offset: number, length: number): Promise<Buffer> {
  const chunks: Buffer[] = [];
  let remaining = length;
  let pos = offset;
  while (remaining > 0) {
    const chunkLength = Math.min(remaining, MAX_SMB_READ_CHUNK);
    const offsetBuffer = new BigInt64(8, pos).toBuffer();
    const data = await requestRaw<Buffer>(handle.client, "read", {
      FileId: handle.fileId,
      Length: chunkLength,
      Offset: offsetBuffer
    });
    if (data.length === 0) break;
    chunks.push(data);
    pos += data.length;
    remaining -= data.length;
  }
  return Buffer.concat(chunks);
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
