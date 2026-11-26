// A pretend Google TV built on the independent `castv2` package (its receiver side). It answers
// like the Default Media Receiver and downloads the media URL (whole file + a seek-style Range
// request) the way a TV would. Writes what it saw to <report.json> when the sender stops it.
import { createRequire } from "node:module";
import { readFileSync, writeFileSync } from "node:fs";
const require = createRequire(new URL("../../media-library-manager/package.json", import.meta.url));
const castv2 = require("castv2");
const [port, keyPem, certPem, reportPath] = process.argv.slice(2);
const NS = { hb: "urn:x-cast:com.google.cast.tp.heartbeat", rx: "urn:x-cast:com.google.cast.receiver", media: "urn:x-cast:com.google.cast.media" };
const server = new castv2.Server({ key: readFileSync(keyPem), cert: readFileSync(certPem) });
const report = { seen: [], fetched: null };
let player = { playerState: "IDLE", currentTime: 0 };
server.on("message", async (client, src, dst, ns, data) => {
  const m = JSON.parse(data);
  report.seen.push(`${ns.split(".").pop()}:${m.type}`);
  const reply = (from, namespace, obj) => server.send(client, from, src, namespace, JSON.stringify(obj));
  if (ns === NS.hb && m.type === "PING") reply(dst, NS.hb, { type: "PONG" });
  if (ns === NS.rx && m.type === "LAUNCH") {
    report.appId = m.appId;
    reply("receiver-0", NS.rx, { type: "RECEIVER_STATUS", requestId: m.requestId, status: { applications: [{ appId: m.appId, sessionId: "sess-1", transportId: "web-7" }] } });
    reply("receiver-0", NS.hb, { type: "PING" });
  }
  if (ns === NS.rx && m.type === "STOP") {
    reply("receiver-0", NS.rx, { type: "RECEIVER_STATUS", requestId: m.requestId, status: { applications: [] } });
    writeFileSync(reportPath, JSON.stringify(report));
  }
  if (ns === NS.media) {
    const status = () => reply("web-7", NS.media, { type: "MEDIA_STATUS", requestId: m.requestId ?? 0, status: [{ mediaSessionId: 1, playerState: player.playerState, currentTime: player.currentTime, media: { duration: 5400 } }] });
    if (m.type === "LOAD") {
      report.loaded = { contentType: m.media.contentType, streamType: m.media.streamType, metadata: m.media.metadata };
      const url = m.media.contentId;
      // A live channel is a remote stream the real Fizz receiver plays; only a served file (library
      // casting) is downloaded here.
      if (m.media.streamType !== "LIVE") {
        const full = Buffer.from(await (await fetch(url)).arrayBuffer());
        const part = await fetch(url, { headers: { Range: "bytes=1000000-1000099" } });
        report.fetched = { contentType: m.media.contentType, title: m.media.metadata.title, size: full.length, sha: (await import("node:crypto")).createHash("sha256").update(full).digest("hex"), rangeStatus: part.status, range: Buffer.from(await part.arrayBuffer()).toString("hex") };
      }
      player = { playerState: "PLAYING", currentTime: 0 };
      status();
    } else if (m.type === "PAUSE") { player.playerState = "PAUSED"; status(); }
    else if (m.type === "PLAY") { player.playerState = "PLAYING"; status(); }
    else if (m.type === "SEEK") { player.currentTime = m.currentTime; status(); }
    else if (m.type === "GET_STATUS") { status(); }
  }
});
server.listen(Number(port), "127.0.0.1", () => console.log("ready"));
