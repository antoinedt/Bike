// "Send to another device" mailbox protocol (compiled to dist-electron by `npm test`), on a plain
// folder standing in for the network share.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readdirSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { RemoteQueue, folderMailbox, magnetName, MAILBOX_ROOT } from "../dist-electron/main/remote/remoteQueue.js";

function pair() {
  const dir = mkdtempSync(path.join(tmpdir(), "mlm-remote-"));
  const mailbox = folderMailbox(dir);
  const got = { pc: [], tv: [], pcResults: [], tvResults: [] };
  const pc = new RemoteQueue(mailbox, { id: "pc-1", name: "Office PC", platform: "windows" }, {
    onJob: async (job) => (got.pc.push(job), { ok: true, message: "Downloading" }),
    onResult: (r) => got.pcResults.push(r)
  });
  const tv = new RemoteQueue(mailbox, { id: "tv-1", name: "Living room TV", platform: "android-tv" }, {
    onJob: async (job) => {
      got.tv.push(job);
      if (job.name === "bad") throw new Error("Not enough space");
      return { ok: true, message: "Downloading", name: "Real Name" };
    },
    onResult: (r) => got.tvResults.push(r)
  });
  return { dir, pc, tv, got };
}

test("devices see each other, not themselves", async () => {
  const { pc, tv } = pair();
  await pc.heartbeat(1000);
  await tv.heartbeat(2000);
  assert.deepEqual((await pc.devices()).map((d) => [d.id, d.name, d.platform, d.lastSeen]), [["tv-1", "Living room TV", "android-tv", 2000]]);
  assert.deepEqual((await tv.devices()).map((d) => d.id), ["pc-1"]);
  await tv.heartbeat(5000);
  assert.equal((await pc.devices())[0].lastSeen, 5000, "heartbeat refreshes");
});

test("a magnet sent to the TV starts there once, and the sender hears back", async () => {
  const { dir, pc, tv, got } = pair();
  const magnet = "magnet:?xt=urn:btih:93494a86fc49124a7a98735d97964b0a8ae5ab88&dn=Some+Movie+%282020%29";
  const job = await pc.send("tv-1", { magnet, name: magnetName(magnet) });
  assert.equal(job.name, "Some Movie (2020)");
  await pc.poll();
  assert.equal(got.pc.length, 0, "not delivered to the sender");
  await tv.poll();
  await tv.poll();
  assert.equal(got.tv.length, 1, "started exactly once");
  assert.equal(got.tv[0].magnet, magnet);
  assert.equal(got.tv[0].fromName, "Office PC");
  assert.deepEqual(readdirSync(path.join(dir, MAILBOX_ROOT, "inbox", "tv-1")), [], "inbox emptied");
  await pc.poll();
  assert.equal(got.pcResults.length, 1);
  assert.deepEqual([got.pcResults[0].ok, got.pcResults[0].toName, got.pcResults[0].name], [true, "Living room TV", "Real Name"]);
  await pc.poll();
  assert.equal(got.pcResults.length, 1, "reply read once");
});

test(".torrent files go through intact, and failures are reported back", async () => {
  const { pc, tv, got } = pair();
  const bytes = Uint8Array.from({ length: 3000 }, (_, i) => i % 251);
  await pc.send("tv-1", { torrent: bytes, name: "file" });
  await pc.send("tv-1", { magnet: "magnet:?xt=urn:btih:aaaa", name: "bad" });
  await tv.poll();
  assert.deepEqual(Buffer.from(got.tv[0].torrent, "base64"), Buffer.from(bytes));
  await pc.poll();
  const failed = got.pcResults.find((r) => !r.ok);
  assert.ok(failed && /Not enough space/.test(failed.message), JSON.stringify(got.pcResults));
});

test("no half-written files are visible", async () => {
  const { dir, pc } = pair();
  await pc.heartbeat();
  const files = readdirSync(path.join(dir, MAILBOX_ROOT, "devices"));
  assert.deepEqual(files, ["pc-1.json"]);
  assert.ok(!existsSync(path.join(dir, MAILBOX_ROOT, "devices", "pc-1.json.tmp")));
});
