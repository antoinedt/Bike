import http from "node:http";
import { randomUUID } from "node:crypto";
import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import type { NetworkShare } from "../../shared/types.js";
import { closeRawFile, openRawFile, readRawStream, type RawFileHandle } from "./smbRawClient.js";

const MIME_BY_EXTENSION: Record<string, string> = {
  mp4: "video/mp4",
  mkv: "video/x-matroska",
  avi: "video/x-msvideo",
  mov: "video/quicktime",
  webm: "video/webm",
  m4v: "video/x-m4v",
  wmv: "video/x-ms-wmv",
  flv: "video/x-flv",
  mpg: "video/mpeg",
  mpeg: "video/mpeg",
  mp3: "audio/mpeg",
  flac: "audio/flac",
  wav: "audio/wav",
  m4a: "audio/mp4",
  ogg: "audio/ogg",
  aac: "audio/aac",
  wma: "audio/x-ms-wma",
  opus: "audio/opus"
};


interface StreamEntry {
  share: NetworkShare | null;
  relPath: string;
  /** A file on this PC instead of a share. */
  localPath?: string;
  mimeType: string;
  handle: Promise<RawFileHandle> | null;
}

const streams = new Map<string, StreamEntry>();
let server: http.Server | null = null;
let serverPort = 0;

export function registerStream(share: NetworkShare, relPath: string, extension: string): string {
  const token = randomUUID();
  streams.set(token, {
    share,
    relPath,
    mimeType: MIME_BY_EXTENSION[extension.toLowerCase()] ?? "application/octet-stream",
    handle: null
  });
  return token;
}

/** Serves a file on this PC to the in-app player (same range support as share streams). */
export function registerLocalStream(localPath: string, extension: string): string {
  const token = randomUUID();
  streams.set(token, {
    share: null,
    relPath: "",
    localPath,
    mimeType: MIME_BY_EXTENSION[extension.toLowerCase()] ?? "application/octet-stream",
    handle: null
  });
  return token;
}

export function unregisterStream(token: string): void {
  const entry = streams.get(token);
  if (!entry) return;
  streams.delete(token);
  if (entry.handle) {
    void entry.handle.then(closeRawFile).catch(() => {});
  }
}

/** Starts the loopback-only relay server on first use and returns its port. */
export async function ensureStreamServer(): Promise<number> {
  if (server) return serverPort;
  server = http.createServer((req, res) => {
    void handleRequest(req, res);
  });
  await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", () => resolve()));
  const address = server.address();
  serverPort = typeof address === "object" && address ? address.port : 0;
  return serverPort;
}

async function handleRequest(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
  const url = new URL(req.url ?? "/", "http://127.0.0.1");
  const token = url.pathname.replace(/^\/stream\//, "");
  const entry = streams.get(token);
  if (!entry) {
    res.writeHead(404).end("Unknown or expired stream token");
    return;
  }

  if (entry.localPath) {
    await serveLocal(entry, req, res);
    return;
  }
  try {
    if (!entry.handle) {
      entry.handle = openRawFile(entry.share!, entry.relPath);
    }
    const handle = await entry.handle;

    let start = 0;
    let end = handle.size - 1;
    let status = 200;
    const rangeHeader = req.headers.range;
    if (rangeHeader) {
      const match = /bytes=(\d+)-(\d*)/.exec(rangeHeader);
      if (match) {
        start = Number(match[1]);
        end = match[2] ? Number(match[2]) : handle.size - 1;
        status = 206;
      }
    }
    end = Math.min(end, handle.size - 1);
    const length = Math.max(0, end - start + 1);

    res.writeHead(status, {
      "Content-Type": entry.mimeType,
      "Accept-Ranges": "bytes",
      "Content-Length": length,
      ...(status === 206 ? { "Content-Range": `bytes ${start}-${end}/${handle.size}` } : {})
    });

    if (req.method === "HEAD" || length === 0) {
      res.end();
      return;
    }

    // Parallel reads (see readRawStream); stops early if the player closes the connection.
    for await (const data of readRawStream(handle, start, end)) {
      if (res.destroyed) break;
      if (!res.write(data)) {
        await new Promise((resolve) => res.once("drain", resolve));
      }
    }
    res.end();
  } catch (err) {
    if (!res.headersSent) res.writeHead(500);
    res.end(`Stream error: ${(err as Error).message ?? err}`);
  }
}

async function serveLocal(entry: StreamEntry, req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
  try {
    const size = (await stat(entry.localPath!)).size;
    let start = 0;
    let end = size - 1;
    let status = 200;
    const match = /bytes=(\d+)-(\d*)/.exec(req.headers.range ?? "");
    if (match) {
      start = Number(match[1]);
      end = match[2] ? Number(match[2]) : size - 1;
      status = 206;
    }
    end = Math.min(end, size - 1);
    const length = Math.max(0, end - start + 1);
    res.writeHead(status, {
      "Content-Type": entry.mimeType,
      "Accept-Ranges": "bytes",
      "Content-Length": length,
      ...(status === 206 ? { "Content-Range": `bytes ${start}-${end}/${size}` } : {})
    });
    if (req.method === "HEAD" || length === 0) {
      res.end();
      return;
    }
    const stream = createReadStream(entry.localPath!, { start, end });
    res.on("close", () => stream.destroy());
    stream.pipe(res);
  } catch (err) {
    if (!res.headersSent) res.writeHead(500);
    res.end(`Stream error: ${(err as Error).message ?? err}`);
  }
}
