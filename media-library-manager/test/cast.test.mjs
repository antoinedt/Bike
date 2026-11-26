// Casting: Cast message encoding (checked against the castv2 package's own protobuf code), DLNA
// device descriptions, and the media server TVs stream from.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { createServer } from "node:http";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { randomBytes } from "node:crypto";
import { tmpdir } from "node:os";
import path from "node:path";
import { encodeCastMessage, decodeCastMessage } from "../dist-electron/main/cast/castv2.js";
import { describeRenderer, parseDuration, dlnaMime, escapeXml } from "../dist-electron/main/cast/dlna.js";
import { publish, unpublish, stopMediaServer } from "../dist-electron/main/cast/mediaServer.js";

const require = createRequire(import.meta.url);
const { CastMessage } = require("castv2/lib/proto.js");
const dir = mkdtempSync(path.join(tmpdir(), "mlm-cast-"));
after(() => {
  stopMediaServer();
  rmSync(dir, { recursive: true, force: true });
});

test("Cast messages round-trip through the castv2 package's protobuf definitions", async () => {
  // castv2 loads its .proto file asynchronously.
  for (let i = 0; i < 100; i++) {
    try {
      CastMessage.serialize({ protocolVersion: 0, sourceId: "a", destinationId: "b", namespace: "c", payloadType: 0, payloadUtf8: "{}" });
      break;
    } catch {
      await new Promise((r) => setTimeout(r, 20));
    }
  }
  const msg = { source: "sender-mlm", destination: "web-7", namespace: "urn:x-cast:com.google.cast.media", data: JSON.stringify({ type: "LOAD", title: "Été 🎬" }) };
  const framed = encodeCastMessage(msg);
  assert.equal(framed.readUInt32BE(0), framed.length - 4, "length prefix");
  const parsed = CastMessage.parse(framed.subarray(4));
  assert.deepEqual(
    [parsed.protocolVersion, parsed.sourceId, parsed.destinationId, parsed.namespace, parsed.payloadType, parsed.payloadUtf8],
    [0, msg.source, msg.destination, msg.namespace, 0, msg.data]
  );
  const theirs = CastMessage.serialize({ protocolVersion: 0, sourceId: "receiver-0", destinationId: "sender-mlm", namespace: "urn:x-cast:com.google.cast.receiver", payloadType: 0, payloadUtf8: '{"type":"RECEIVER_STATUS"}' });
  assert.deepEqual(decodeCastMessage(theirs), { source: "receiver-0", destination: "sender-mlm", namespace: "urn:x-cast:com.google.cast.receiver", data: '{"type":"RECEIVER_STATUS"}' });
});

test("reads a TV's DLNA description (relative control URL, escaped name)", async () => {
  const xml = `<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>[TV] Samsung Q60 &amp; Co</friendlyName>
    <UDN>uuid:abc-123</UDN><serviceList>
      <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL></service>
      <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>upnp/control/AVTransport1</controlURL></service>
    </serviceList></device></root>`;
  const server = createServer((_req, res) => res.end(xml));
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const port = server.address().port;
  const d = await describeRenderer(`http://127.0.0.1:${port}/dmr/desc.xml`);
  server.close();
  assert.deepEqual(d, { id: "uuid:abc-123", name: "[TV] Samsung Q60 & Co", host: "127.0.0.1", controlUrl: `http://127.0.0.1:${port}/dmr/upnp/control/AVTransport1` });
  assert.equal(parseDuration("01:02:03.500"), 3723.5);
  assert.equal(parseDuration("NOT_IMPLEMENTED"), 0);
  assert.equal(dlnaMime("video/x-matroska"), "video/x-mkv");
  assert.equal(escapeXml(`<a href="x">&'</a>`), "&lt;a href=&quot;x&quot;&gt;&amp;&apos;&lt;/a&gt;");
});

test("media server: full file, ranges, HEAD, DLNA headers, and the link dies when unpublished", async () => {
  const bytes = randomBytes(200_000);
  const file = path.join(dir, "clip.mkv");
  writeFileSync(file, bytes);
  const { url, token, contentType } = await publish({ kind: "local", path: file }, "Clip (1).mkv", "mkv", "127.0.0.1");
  assert.equal(contentType, "video/x-matroska");
  const local = url.replace(/^http:\/\/[^/]+/, (m) => m.replace(/\/\/[^:]+/, "//127.0.0.1"));

  const full = await fetch(local);
  assert.equal(full.status, 200);
  assert.ok(Buffer.from(await full.arrayBuffer()).equals(bytes));
  assert.equal(full.headers.get("accept-ranges"), "bytes");

  const part = await fetch(local, { headers: { Range: "bytes=1000-1999" } });
  assert.equal(part.status, 206);
  assert.equal(part.headers.get("content-range"), `bytes 1000-1999/${bytes.length}`);
  assert.ok(Buffer.from(await part.arrayBuffer()).equals(bytes.subarray(1000, 2000)));

  const suffix = await fetch(local, { headers: { Range: "bytes=-10" } });
  assert.ok(Buffer.from(await suffix.arrayBuffer()).equals(bytes.subarray(-10)));
  assert.equal((await fetch(local, { headers: { Range: `bytes=${bytes.length}-` } })).status, 416);

  const head = await fetch(local, { method: "HEAD", headers: { "getcontentFeatures.dlna.org": "1" } });
  assert.equal(head.headers.get("content-type"), "video/x-mkv", "Samsung-style MIME for DLNA requests");
  assert.match(head.headers.get("contentfeatures.dlna.org") ?? "", /DLNA\.ORG_OP=01/);
  assert.equal(head.headers.get("content-length"), String(bytes.length));

  unpublish(token);
  assert.equal((await fetch(local)).status, 404);
});
