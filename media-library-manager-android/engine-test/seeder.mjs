// Seeds test files with WebTorrent (no DHT/trackers) and serves .torrent/redirect over HTTP.
import { createServer } from "node:http";
import { randomBytes } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
const APP = new URL("../../media-library-manager", import.meta.url).pathname;
const { default: WebTorrent } = await import(`${APP}/node_modules/webtorrent/index.js`);
const dir = process.argv[2];
mkdirSync(path.join(dir, "Show Season 1"), { recursive: true });
writeFileSync(path.join(dir, "movie.mkv"), randomBytes(6_000_000));
writeFileSync(path.join(dir, "Show Season 1", "ep1.mp4"), randomBytes(1_500_000));
writeFileSync(path.join(dir, "Show Season 1", "ep2.mp4"), randomBytes(1_200_000));
writeFileSync(path.join(dir, "big.mkv"), randomBytes(40_000_000));
const client = new WebTorrent({ dht: false, tracker: false, lsd: false, natUpnp: false, natPmp: false, utp: false, uploadLimit: 4_000_000 });
const out = {};
for (const name of ["movie.mkv", "Show Season 1", "big.mkv"]) {
  const t = await new Promise((r) => client.seed(path.join(dir, name), { announce: [] }, r));
  out[name] = { magnet: t.magnetURI, infoHash: t.infoHash, torrent: Buffer.from(t.torrentFile).toString("base64") };
}
const http = createServer((req, res) => {
  if (req.url === "/movie.torrent") { res.writeHead(200); res.end(Buffer.from(out["movie.mkv"].torrent, "base64")); }
  else if (req.url === "/to-magnet") { res.writeHead(302, { Location: out["movie.mkv"].magnet }); res.end(); }
  else { res.writeHead(404); res.end(); }
});
await new Promise((r) => http.listen(0, "127.0.0.1", r));
console.log(JSON.stringify({ port: client.torrentPort, http: `http://127.0.0.1:${http.address().port}`, torrents: out }));
