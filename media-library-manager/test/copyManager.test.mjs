// Tests "Copy to…" (CopyManager, compiled to dist-electron by `npm test`).
// Local-folder copies always run. The network-share cases need a test SMB server; point
// MLM_SMB_TEST at it as "host:port|share|user|password|<that share's folder on this machine>",
// e.g. an impacket smbserver on 127.0.0.1:1445. They're skipped otherwise. The smb2 package signs
// in with NTLM, which needs DES/MD4: Electron has them, plain Node 17+ only with
// --openssl-legacy-provider (the npm test script passes it).
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { CopyManager, numberedName } from "../dist-electron/main/transfer/copyManager.js";

const root = mkdtempSync(path.join(tmpdir(), "mlm-copy-test-"));
after(() => rmSync(root, { recursive: true, force: true }));

const libA = path.join(root, "libA");
const libB = path.join(root, "libB");
mkdirSync(path.join(libA, "Movies"), { recursive: true });
mkdirSync(libB, { recursive: true });
const movieBytes = randomBytes(3_500_000);
writeFileSync(path.join(libA, "Movies", "Film.mkv"), movieBytes);

const smb = process.env.MLM_SMB_TEST?.split("|");
const share = smb && { id: "s1", label: "NAS", host: smb[0], share: smb[1], subPath: "", domain: "WORKGROUP", username: smb[2], password: smb[3] };
const shareDir = smb?.[4];

const settings = {
  localFolders: [
    { id: "a", path: libA, label: "Library A" },
    { id: "b", path: libB, label: "Library B" }
  ],
  networkShares: share ? [share] : []
};

function localItem(file) {
  return {
    id: file, name: path.basename(file), path: file, kind: "video", extension: "mkv",
    sizeBytes: readFileSync(file).length, modifiedAt: 0, source: { type: "local", folderId: "a" }
  };
}

function manager() {
  const copied = [];
  const m = new CopyManager({ settings: () => settings, onCopied: (job) => copied.push(job) });
  return { m, copied };
}

test("numbered names", () => {
  assert.equal(numberedName("Film.mkv", 1), "Film.mkv");
  assert.equal(numberedName("Film.mkv", 2), "Film (2).mkv");
  assert.equal(numberedName("README", 3), "README (3)");
});

test("copies a local file into a subfolder of another library folder, never overwriting", async () => {
  const { m, copied } = manager();
  const item = localItem(path.join(libA, "Movies", "Film.mkv"));
  const target = { kind: "folder", folderId: "b", relPath: "Films/2024" };
  m.enqueue(item, target);
  m.enqueue(item, target);
  await m.idle();
  const jobs = m.list();
  assert.deepEqual(jobs.map((j) => j.state), ["done", "done"], JSON.stringify(jobs));
  assert.equal(jobs[0].targetLabel, "Library B/Films/2024");
  assert.ok(jobs.every((j) => j.copiedBytes === j.totalBytes && j.totalBytes === movieBytes.length), "done jobs report full progress");
  assert.equal(jobs[1].savedAs, "Film (2).mkv");
  assert.equal(copied.length, 2);
  for (const name of ["Film.mkv", "Film (2).mkv"]) {
    assert.ok(readFileSync(path.join(libB, "Films", "2024", name)).equals(movieBytes), `${name} identical`);
  }
});

test("lists and creates folders, and refuses paths outside the library folder", async () => {
  const { m } = manager();
  await m.createFolder({ kind: "folder", folderId: "b", relPath: "" }, "New stuff");
  const folders = await m.listFolders({ kind: "folder", folderId: "b", relPath: "" });
  assert.deepEqual(folders, ["Films", "New stuff"]);
  m.enqueue(localItem(path.join(libA, "Movies", "Film.mkv")), { kind: "folder", folderId: "b", relPath: "../../escape" });
  await m.idle();
  assert.equal(m.list()[0].state, "failed");
  assert.match(m.list()[0].error, /isn't a usable name/);
  assert.ok(!existsSync(path.join(root, "escape")));
});

test("a failed copy reports why and leaves no partial file", async () => {
  const { m } = manager();
  const missing = { ...localItem(path.join(libA, "Movies", "Film.mkv")), path: path.join(libA, "nope.mkv"), name: "nope.mkv" };
  m.enqueue(missing, { kind: "path", path: path.join(root, "loose") });
  await m.idle();
  const [job] = m.list();
  assert.equal(job.state, "failed");
  assert.match(job.error, /ENOENT/);
  assert.ok(!existsSync(path.join(root, "loose", "nope.mkv")));
});

test("network share: local -> share, share -> share, share -> local", { skip: !share && "set MLM_SMB_TEST to run" }, async () => {
  const { m } = manager();
  const item = localItem(path.join(libA, "Movies", "Film.mkv"));
  m.enqueue(item, { kind: "share", shareId: "s1", relPath: "Incoming/Films" });
  m.enqueue(item, { kind: "share", shareId: "s1", relPath: "Incoming/Films" });
  await m.idle();
  assert.deepEqual(m.list().map((j) => j.state), ["done", "done"], JSON.stringify(m.list()));
  assert.equal(m.list()[1].savedAs, "Film (2).mkv");
  assert.ok(m.list().every((j) => j.copiedBytes === j.totalBytes), "done jobs report full progress");
  assert.ok(readFileSync(path.join(shareDir, "Incoming", "Films", "Film.mkv")).equals(movieBytes), "local -> share identical");
  assert.ok(readFileSync(path.join(shareDir, "Incoming", "Films", "Film (2).mkv")).equals(movieBytes), "second copy identical");

  assert.deepEqual(await m.listFolders({ kind: "share", shareId: "s1", relPath: "Incoming" }), ["Films"]);
  await m.createFolder({ kind: "share", shareId: "s1", relPath: "Incoming" }, "Archive");
  assert.deepEqual(await m.listFolders({ kind: "share", shareId: "s1", relPath: "Incoming" }), ["Archive", "Films"]);

  const onShare = {
    id: "n1", name: "Film.mkv", path: `smb://${share.host}/${share.share}/Incoming/Films/Film.mkv`, kind: "video",
    extension: "mkv", sizeBytes: 0, modifiedAt: 0, source: { type: "network", shareId: "s1" }
  };
  m.clearFinished();
  m.enqueue(onShare, { kind: "share", shareId: "s1", relPath: "Incoming/Archive" });
  m.enqueue(onShare, { kind: "folder", folderId: "b", relPath: "From NAS" });
  await m.idle();
  assert.deepEqual(m.list().map((j) => j.state), ["done", "done"], JSON.stringify(m.list()));
  assert.ok(readFileSync(path.join(shareDir, "Incoming", "Archive", "Film.mkv")).equals(movieBytes), "share -> share identical");
  assert.ok(readFileSync(path.join(libB, "From NAS", "Film.mkv")).equals(movieBytes), "share -> local identical");
  assert.deepEqual(readdirSync(path.join(shareDir, "Incoming", "Archive")), ["Film.mkv"]);
});
