import { createReadStream, promises as fs } from "node:fs";
import http from "node:http";
import { randomUUID } from "node:crypto";
import os from "node:os";
import type { NetworkShare } from "../../shared/types.js";
import { closeRawFile, openRawFile, readRawStream } from "../network/smbRawClient.js";
import { DLNA_FEATURES, dlnaMime } from "./dlna.js";

// Serves a library item to a TV on the local network (the TV fetches the video itself): a local
// file, or a file on an SMB share relayed through the app. Supports Range requests (seeking) and
// the DLNA headers Samsung TVs look for. Each item gets an unguessable URL that stops working
// when casting ends.

export type MediaSource = { kind: "local"; path: string } | { kind: "smb"; share: NetworkShare; relPath: string };

interface Entry {
  source: MediaSource;
  contentType: string;
  name: string;
}

const MIME: Record<string, string> = {
  mp4: "video/mp4", m4v: "video/mp4", mkv: "video/x-matroska", webm: "video/webm", avi: "video/x-msvideo",
  mov: "video/quicktime", wmv: "video/x-ms-wmv", mpg: "video/mpeg", mpeg: "video/mpeg", ts: "video/mp2t",
  mp3: "audio/mpeg", m4a: "audio/mp4", aac: "audio/aac", flac: "audio/flac", wav: "audio/wav", ogg: "audio/ogg",
  opus: "audio/ogg", jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png", gif: "image/gif", webp: "image/webp"
};

export function contentTypeFor(extension: string): string {
  return MIME[extension.toLowerCase().replace(/^\./, "")] ?? "application/octet-stream";
}

const entries = new Map<string, Entry>();
let server: http.Server | null = null;
let port = 0;

/** The address of this computer that a device at `deviceHost` can reach (same subnet if possible). */
export function lanAddressFor(deviceHost: string): string {
  const candidates = Object.values(os.networkInterfaces())
    .flat()
    .filter((i): i is os.NetworkInterfaceInfo => !!i && i.family === "IPv4" && !i.internal);
  const prefix = deviceHost.split(".").slice(0, 3).join(".");
  return (candidates.find((i) => i.address.startsWith(prefix + ".")) ?? candidates[0])?.address ?? "127.0.0.1";
}

async function ensureServer(): Promise<number> {
  if (server) return port;
  server = http.createServer((req, res) => void handle(req, res));
  await new Promise<void>((resolve) => server!.listen(0, "0.0.0.0", () => resolve()));
  port = (server.address() as { port: number }).port;
  return port;
}

/** Makes `source` available to devices on the network; returns its URL as seen from `deviceHost`. */
export async function publish(source: MediaSource, name: string, extension: string, deviceHost: string): Promise<{ url: string; token: string; contentType: string }> {
  const p = await ensureServer();
  const token = randomUUID();
  const contentType = contentTypeFor(extension);
  entries.set(token, { source, contentType, name });
  const safeName = encodeURIComponent(name.replace(/[/\\?#]/g, "_"));
  return { url: `http://${lanAddressFor(deviceHost)}:${p}/cast/${token}/${safeName}`, token, contentType };
}

export function unpublish(token: string): void {
  entries.delete(token);
}

async function sizeOf(source: MediaSource): Promise<number> {
  if (source.kind === "local") return (await fs.stat(source.path)).size;
  const handle = await openRawFile(source.share, source.relPath);
  await closeRawFile(handle);
  return handle.size;
}

async function handle(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
  const token = /^\/cast\/([0-9a-f-]{36})(\/|$)/.exec(req.url ?? "")?.[1];
  const entry = token ? entries.get(token) : undefined;
  if (!entry) {
    res.writeHead(404).end();
    return;
  }
  try {
    const size = await sizeOf(entry.source);
    let start = 0;
    let end = size - 1;
    const range = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range ?? "");
    if (range) {
      if (range[1] === "" && range[2] !== "") {
        start = Math.max(0, size - Number(range[2]));
      } else {
        start = Number(range[1] || 0);
        if (range[2]) end = Math.min(Number(range[2]), size - 1);
      }
      if (start >= size || start > end) {
        res.writeHead(416, { "Content-Range": `bytes */${size}` }).end();
        return;
      }
    }
    const headers: http.OutgoingHttpHeaders = {
      "Content-Type": req.headers["getcontentfeatures.dlna.org"] ? dlnaMime(entry.contentType) : entry.contentType,
      "Accept-Ranges": "bytes",
      "Content-Length": end - start + 1,
      "transferMode.dlna.org": "Streaming",
      "contentFeatures.dlna.org": DLNA_FEATURES,
      Connection: "close"
    };
    if (range) headers["Content-Range"] = `bytes ${start}-${end}/${size}`;
    res.writeHead(range ? 206 : 200, headers);
    if (req.method === "HEAD") {
      res.end();
      return;
    }
    if (entry.source.kind === "local") {
      const stream = createReadStream(entry.source.path, { start, end });
      stream.pipe(res);
      res.on("close", () => stream.destroy());
      return;
    }
    const fileHandle = await openRawFile(entry.source.share, entry.source.relPath);
    try {
      for await (const chunk of readRawStream(fileHandle, start, end)) {
        if (res.destroyed) break;
        if (!res.write(chunk)) await new Promise((r) => res.once("drain", r));
      }
      res.end();
    } finally {
      await closeRawFile(fileHandle);
    }
  } catch (err) {
    if (!res.headersSent) res.writeHead(500);
    res.end(String((err as Error).message ?? err));
  }
}

export function stopMediaServer(): void {
  server?.close();
  server = null;
  entries.clear();
}
