// Tests the app's TorrentManager (compiled to dist-electron by `npm test`) against a real
// WebTorrent seeder on localhost: no internet, trackers or DHT involved.
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { randomBytes } from "node:crypto";
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import WebTorrent from "webtorrent";
import { TorrentManager } from "../dist-electron/main/torrent/torrentManager.js";

const LOCAL_ONLY = { dht: false, tracker: false, lsd: false, natUpnp: false, natPmp: false };
const root = mkdtempSync(path.join(tmpdir(), "mlm-torrent-test-"));
const seedDir = path.join(root, "seed");
let seeder;
let httpServer;
let httpBase;
const torrents = {}; // name -> seeded torrent

function memoryPersistence() {
  let saved = [];
  return { load: () => saved.map((e) => ({ ...e })), save: (entries) => (saved = entries.map((e) => ({ ...e }))), peek: () => saved };
}

/** Points the manager's (private) WebTorrent client at the local seeder for one torrent. */
async function connectToSeeder(manager, infoHash) {
  // WebTorrent fills in infoHash asynchronously after add(), so wait for the torrent to appear.
  const t = await waitFor(() => manager["client"].torrents.find((x) => x.infoHash === infoHash), 10000);
  t.addPeer(`127.0.0.1:${seeder.torrentPort}`);
}

function waitFor(predicate, timeoutMs = 30000) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const iv = setInterval(() => {
      const value = predicate();
      if (value) { clearInterval(iv); resolve(value); }
      else if (Date.now() - started > timeoutMs) { clearInterval(iv); reject(new Error("timed out")); }
    }, 50);
  });
}

const sameBytes = (a, b) => readFileSync(a).equals(readFileSync(b));

before(async () => {
  mkdirSync(path.join(seedDir, "Show Season 1"), { recursive: true });
  writeFileSync(path.join(seedDir, "movie.mkv"), randomBytes(6_000_000));
  writeFileSync(path.join(seedDir, "Show Season 1", "ep1.mp4"), randomBytes(1_500_000));
  writeFileSync(path.join(seedDir, "Show Season 1", "ep2.mp4"), randomBytes(1_200_000));
  writeFileSync(path.join(seedDir, "big.mkv"), randomBytes(40_000_000));
  seeder = new WebTorrent(LOCAL_ONLY);
  for (const name of ["movie.mkv", "Show Season 1", "big.mkv"]) {
    torrents[name] = await new Promise((resolve) => seeder.seed(path.join(seedDir, name), { announce: [] }, resolve));
  }
  httpServer = createServer((req, res) => {
    if (req.url === "/movie.torrent") {
      res.writeHead(200, { "Content-Type": "application/x-bittorrent" });
      res.end(Buffer.from(torrents["movie.mkv"].torrentFile));
    } else if (req.url === "/to-magnet") {
      res.writeHead(302, { Location: torrents["movie.mkv"].magnetURI });
      res.end();
    } else {
      res.writeHead(404);
      res.end();
    }
  });
  await new Promise((r) => httpServer.listen(0, "127.0.0.1", r));
  httpBase = `http://127.0.0.1:${httpServer.address().port}`;
});

after(async () => {
  httpServer?.closeAllConnections();
  httpServer?.close();
  await new Promise((r) => seeder.destroy(r));
});

test("downloads a single-file torrent from a magnet link and stops when done", async () => {
  const done = [];
  const manager = new TorrentManager(memoryPersistence(), { clientOptions: LOCAL_ONLY, onComplete: (t) => done.push(t.id) });
  const dest = path.join(root, "dl1");
  const status = await manager.add(torrents["movie.mkv"].magnetURI, dest);
  assert.equal(status.id, torrents["movie.mkv"].infoHash);
  await connectToSeeder(manager, status.id);
  await waitFor(() => done.length === 1);
  const [s] = manager.list();
  assert.equal(s.state, "done");
  assert.equal(s.progress, 1);
  assert.equal(s.name, "movie.mkv");
  assert.ok(sameBytes(path.join(seedDir, "movie.mkv"), path.join(dest, "movie.mkv")), "file identical");
  await waitFor(() => !manager.hasActive());
  await manager.shutdown();
});

test("downloads a multi-file torrent from .torrent file bytes into its own folder", async () => {
  const done = [];
  const manager = new TorrentManager(memoryPersistence(), { clientOptions: LOCAL_ONLY, onComplete: (t) => done.push(t) });
  const dest = path.join(root, "dl2");
  const status = await manager.add(torrents["Show Season 1"].torrentFile, dest);
  assert.equal(status.name, "Show Season 1", "name known straight away from the .torrent file");
  await connectToSeeder(manager, status.id);
  await waitFor(() => done.length === 1);
  for (const ep of ["ep1.mp4", "ep2.mp4"]) {
    assert.ok(sameBytes(path.join(seedDir, "Show Season 1", ep), path.join(dest, "Show Season 1", ep)), `${ep} identical`);
  }
  assert.equal(manager.pathFor(status.id), path.join(dest, "Show Season 1"));
  await manager.shutdown();
});

test("adds from an http .torrent URL and from a link that redirects to a magnet", async () => {
  const manager = new TorrentManager(memoryPersistence(), { clientOptions: LOCAL_ONLY });
  const viaUrl = await manager.add(`${httpBase}/movie.torrent`, path.join(root, "dl3"));
  assert.equal(viaUrl.id, torrents["movie.mkv"].infoHash);
  assert.equal(viaUrl.name, "movie.mkv");
  await manager.remove(viaUrl.id, false);
  const viaRedirect = await manager.add(`${httpBase}/to-magnet`, path.join(root, "dl3"));
  assert.equal(viaRedirect.id, torrents["movie.mkv"].infoHash);
  await manager.shutdown();
});

test("adding the same torrent twice keeps one entry", async () => {
  const manager = new TorrentManager(memoryPersistence(), { clientOptions: LOCAL_ONLY });
  await manager.add(torrents["movie.mkv"].magnetURI, path.join(root, "dl4"));
  await manager.add(torrents["movie.mkv"].torrentFile, path.join(root, "dl4"));
  assert.equal(manager.list().length, 1);
  await manager.shutdown();
});

test("rejects things that aren't torrents", async () => {
  const manager = new TorrentManager(memoryPersistence(), { clientOptions: LOCAL_ONLY });
  await assert.rejects(manager.add("not a magnet", path.join(root, "x")), /magnet link/);
  await assert.rejects(manager.add(`${httpBase}/missing.torrent`, path.join(root, "x")), /HTTP 404/);
  await assert.rejects(manager.add(new Uint8Array([1, 2, 3]), path.join(root, "x")), /valid magnet link or \.torrent/);
  await assert.rejects(manager.add("file:///etc/passwd", path.join(root, "x")), /magnet link/);
  assert.equal(manager.list().length, 0);
  await manager.shutdown();
});

test("pause keeps partial data, resume continues, and the result is complete", async () => {
  const done = [];
  const persistence = memoryPersistence();
  const manager = new TorrentManager(persistence, { clientOptions: LOCAL_ONLY, onComplete: (t) => done.push(t) });
  const dest = path.join(root, "dl5");
  const { id } = await manager.add(torrents["big.mkv"].magnetURI, dest);
  await connectToSeeder(manager, id);
  await waitFor(() => manager.list()[0].progress > 0.1);
  manager.pause(id);
  const paused = manager.list()[0];
  assert.equal(paused.state, "paused");
  assert.ok(paused.progress > 0.1 && paused.progress < 1, `progress kept while paused (${paused.progress})`);
  assert.equal(paused.downloadSpeed, 0);
  assert.equal(persistence.peek()[0].paused, true);
  assert.ok(persistence.peek()[0].torrentFile, ".torrent saved so resume doesn't need peers for metadata");

  manager.resume(id);
  await connectToSeeder(manager, id);
  await waitFor(() => done.length === 1, 60000);
  assert.ok(sameBytes(path.join(seedDir, "big.mkv"), path.join(dest, "big.mkv")), "file identical after pause/resume");
  await manager.shutdown();
});

test("restores saved downloads after a restart and resumes unfinished ones", async () => {
  const persistence = memoryPersistence();
  const first = new TorrentManager(persistence, { clientOptions: LOCAL_ONLY });
  const dest = path.join(root, "dl6");
  const { id } = await first.add(torrents["big.mkv"].magnetURI, dest);
  await connectToSeeder(first, id);
  await waitFor(() => first.list()[0].progress > 0.05);
  await first.shutdown(); // app quits mid-download

  const done = [];
  const second = new TorrentManager(persistence, { clientOptions: LOCAL_ONLY, onComplete: (t) => done.push(t) });
  second.restore();
  assert.equal(second.list().length, 1);
  assert.notEqual(second.list()[0].state, "paused", "unfinished download restarts by itself");
  await connectToSeeder(second, id);
  await waitFor(() => done.length === 1, 60000);
  assert.ok(sameBytes(path.join(seedDir, "big.mkv"), path.join(dest, "big.mkv")), "file identical after restart");
  await second.shutdown();

  const third = new TorrentManager(persistence, { clientOptions: LOCAL_ONLY });
  third.restore();
  assert.equal(third.list()[0].state, "done", "finished downloads stay finished and aren't restarted");
  assert.equal(third.hasActive(), false);
  await third.shutdown();
});

test("remove keeps files unless asked to delete them, and only deletes the download's own files", async () => {
  const done = [];
  const manager = new TorrentManager(memoryPersistence(), { clientOptions: LOCAL_ONLY, onComplete: (t) => done.push(t) });
  const dest = path.join(root, "dl7");
  mkdirSync(dest, { recursive: true });
  writeFileSync(path.join(dest, "unrelated.txt"), "keep me");
  const a = await manager.add(torrents["movie.mkv"].magnetURI, dest);
  const b = await manager.add(torrents["Show Season 1"].magnetURI, dest);
  await connectToSeeder(manager, a.id);
  await connectToSeeder(manager, b.id);
  await waitFor(() => done.length === 2);

  await manager.remove(a.id, false);
  assert.ok(existsSync(path.join(dest, "movie.mkv")), "kept when not asked to delete");
  await manager.remove(b.id, true);
  assert.ok(!existsSync(path.join(dest, "Show Season 1")), "multi-file folder deleted");
  assert.ok(existsSync(path.join(dest, "unrelated.txt")), "other files in the folder untouched");
  assert.equal(manager.list().length, 0);
  await manager.shutdown();
});
