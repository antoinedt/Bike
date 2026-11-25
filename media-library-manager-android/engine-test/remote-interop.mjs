// The desktop side of the "Send to another device" interop check; see RemoteInterop.kt.
const APP = new URL("../../media-library-manager/dist-electron/main", import.meta.url).pathname;
const { RemoteQueue, folderMailbox } = await import(`${APP}/remote/remoteQueue.js`);
const [root, step] = process.argv.slice(2);
const jobs = [];
const results = [];
const pc = new RemoteQueue(folderMailbox(root), { id: "pc-1", name: "Office PC", platform: "windows" }, {
  onJob: async (job) => (jobs.push(job), { ok: true, message: "Downloading" }),
  onResult: (r) => results.push(r)
});
const fail = (msg) => { console.log(`FAIL ${msg}`); process.exit(1); };
if (step === "send") {
  await pc.heartbeat();
  await pc.send("tv-1", { magnet: "magnet:?xt=urn:btih:aaaa&dn=Some+Movie", name: "Some Movie" });
  await pc.send("tv-1", { torrent: Uint8Array.from({ length: 3000 }, (_, i) => i % 251), name: "file" });
  console.log("ok   PC sent a magnet and a .torrent to the TV");
} else if (step === "receive") {
  const devices = await pc.devices();
  if (devices.length !== 1 || devices[0].name !== "Living room TV" || devices[0].platform !== "android-tv") fail(`PC sees the TV: ${JSON.stringify(devices)}`);
  await pc.poll();
  if (jobs.length !== 1 || jobs[0].magnet !== "magnet:?xt=urn:btih:bbbb&dn=From+TV" || jobs[0].fromName !== "Living room TV") fail(`PC got the TV's job: ${JSON.stringify(jobs)}`);
  if (results.length !== 2 || !results.every((r) => r.ok && r.toName === "Living room TV")) fail(`PC heard back twice: ${JSON.stringify(results)}`);
  if (!results.some((r) => r.name === "From torrent file")) fail("reply carries the name the TV used");
  console.log("ok   PC received the TV's job and both replies");
}
