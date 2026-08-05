import { shell } from "electron";
import { promises as fs } from "node:fs";
import os from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";

const USER_AGENT = "Mozilla/5.0 (Media Library Manager; +desktop app) media-library-manager/0.1";

/**
 * Hands a download off to the OS's default handler: magnet links go straight to
 * shell.openExternal (which invokes whatever torrent client is registered for the magnet:
 * protocol); .torrent URLs are downloaded to a temp file first, then opened with
 * shell.openPath so the OS's default .torrent handler picks it up. Only http(s) and magnet
 * URLs are accepted to avoid shell.openExternal being handed an arbitrary/unexpected scheme.
 */
export async function handOffDownload(url: string): Promise<void> {
  if (url.startsWith("magnet:")) {
    await shell.openExternal(url);
    return;
  }

  if (!/^https?:\/\//i.test(url)) {
    throw new Error(`Refusing to open unrecognized download URL scheme: ${url}`);
  }

  const res = await fetch(url, { headers: { "User-Agent": USER_AGENT } });
  if (!res.ok) {
    throw new Error(`Failed to download torrent file (status ${res.status})`);
  }
  const buffer = Buffer.from(await res.arrayBuffer());

  const destDir = await fs.mkdtemp(path.join(os.tmpdir(), "media-library-torrent-"));
  const fileName = decodeURIComponent(path.basename(new URL(url).pathname)) || `${randomUUID()}.torrent`;
  const destPath = path.join(destDir, fileName.endsWith(".torrent") ? fileName : `${fileName}.torrent`);
  await fs.writeFile(destPath, buffer);

  const openError = await shell.openPath(destPath);
  if (openError) {
    throw new Error(`Downloaded the .torrent file but couldn't open it: ${openError}`);
  }
}
