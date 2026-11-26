import dgram from "node:dgram";

// DLNA / UPnP AV "MediaRenderer" control, which Samsung (and most other) smart TVs have built in:
// find renderers with an SSDP search, then drive their AVTransport service with SOAP calls.

export interface DlnaDevice {
  id: string;
  name: string;
  host: string;
  /** AVTransport control URL. */
  controlUrl: string;
}

const SSDP_ADDRESS = "239.255.255.250";
const AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";

function tag(xml: string, name: string): string | undefined {
  const match = new RegExp(`<(?:\\w+:)?${name}[^>]*>([\\s\\S]*?)</(?:\\w+:)?${name}>`, "i").exec(xml);
  return match ? decodeXml(match[1].trim()) : undefined;
}

function decodeXml(s: string): string {
  return s
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, "&");
}

export function escapeXml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&apos;");
}

/** Reads a renderer's description document; null if it has no AVTransport service. */
export async function describeRenderer(location: string): Promise<DlnaDevice | null> {
  const res = await fetch(location, { signal: AbortSignal.timeout(4000) });
  if (!res.ok) return null;
  const xml = await res.text();
  const services = xml.match(/<service>[\s\S]*?<\/service>/gi) ?? [];
  const av = services.find((s) => (tag(s, "serviceType") ?? "").startsWith("urn:schemas-upnp-org:service:AVTransport:"));
  const control = av && tag(av, "controlURL");
  if (!control) return null;
  const base = tag(xml, "URLBase") || location;
  return {
    id: tag(xml, "UDN") ?? location,
    name: tag(xml, "friendlyName") ?? new URL(location).hostname,
    host: new URL(location).hostname,
    controlUrl: new URL(control, base).toString()
  };
}

/** Looks for DLNA renderers on the local network for `timeoutMs`. */
export async function discoverDlna(timeoutMs = 3000): Promise<DlnaDevice[]> {
  const locations = new Set<string>();
  const socket = dgram.createSocket({ type: "udp4", reuseAddr: true });
  socket.on("message", (msg) => {
    const location = /^location:\s*(.+)$/im.exec(msg.toString())?.[1]?.trim();
    if (location) locations.add(location);
  });
  await new Promise<void>((resolve) => socket.bind(0, () => resolve()));
  const search = Buffer.from(
    [
      "M-SEARCH * HTTP/1.1",
      `HOST: ${SSDP_ADDRESS}:1900`,
      'MAN: "ssdp:discover"',
      "MX: 2",
      "ST: urn:schemas-upnp-org:device:MediaRenderer:1",
      "",
      ""
    ].join("\r\n")
  );
  for (let i = 0; i < 3; i++) {
    socket.send(search, 1900, SSDP_ADDRESS);
    await new Promise((r) => setTimeout(r, Math.min(400, timeoutMs / 3)));
  }
  await new Promise((r) => setTimeout(r, Math.max(0, timeoutMs - 1200)));
  socket.close();
  const devices = await Promise.all([...locations].map((l) => describeRenderer(l).catch(() => null)));
  const byId = new Map<string, DlnaDevice>();
  for (const d of devices) if (d) byId.set(d.id, d);
  return [...byId.values()];
}

/** "01:02:03" → 3723 seconds. */
export function parseDuration(value: string | undefined): number {
  if (!value || !/^\d+:\d{2}:\d{2}/.test(value)) return 0;
  const [h, m, s] = value.split(":");
  return Number(h) * 3600 + Number(m) * 60 + parseFloat(s);
}

function formatDuration(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 3600)}:${String(Math.floor((s % 3600) / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

/** The protocolInfo DLNA flags TVs expect for a seekable HTTP stream. */
export const DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";

/** MIME type Samsung TVs recognise for a few containers that have several names. */
export function dlnaMime(contentType: string): string {
  if (contentType === "video/x-matroska") return "video/x-mkv";
  return contentType;
}

export class DlnaRenderer {
  constructor(readonly device: DlnaDevice) {}

  private async soap(action: string, args: string): Promise<string> {
    const body =
      '<?xml version="1.0" encoding="utf-8"?>' +
      '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">' +
      `<s:Body><u:${action} xmlns:u="${AVTRANSPORT}"><InstanceID>0</InstanceID>${args}</u:${action}></s:Body></s:Envelope>`;
    const res = await fetch(this.device.controlUrl, {
      method: "POST",
      headers: { "Content-Type": 'text/xml; charset="utf-8"', SOAPAction: `"${AVTRANSPORT}#${action}"` },
      body,
      signal: AbortSignal.timeout(8000)
    });
    const text = await res.text();
    if (!res.ok) {
      const detail = tag(text, "errorDescription") ?? tag(text, "faultstring") ?? `HTTP ${res.status}`;
      throw new Error(`The TV refused ${action}: ${detail}`);
    }
    return text;
  }

  /** Points the TV at `url` and starts playing. */
  async load(url: string, contentType: string, title: string, kind: "video" | "audio" | "image"): Promise<void> {
    const mime = dlnaMime(contentType);
    const upnpClass = kind === "audio" ? "object.item.audioItem.musicTrack" : kind === "image" ? "object.item.imageItem.photo" : "object.item.videoItem";
    const didl =
      '<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">' +
      `<item id="0" parentID="-1" restricted="1"><dc:title>${escapeXml(title)}</dc:title><upnp:class>${upnpClass}</upnp:class>` +
      `<res protocolInfo="http-get:*:${mime}:${DLNA_FEATURES}">${escapeXml(url)}</res></item></DIDL-Lite>`;
    await this.stop().catch(() => undefined);
    await this.soap("SetAVTransportURI", `<CurrentURI>${escapeXml(url)}</CurrentURI><CurrentURIMetaData>${escapeXml(didl)}</CurrentURIMetaData>`);
    await this.play();
  }

  play(): Promise<string> {
    return this.soap("Play", "<Speed>1</Speed>");
  }

  pause(): Promise<string> {
    return this.soap("Pause", "");
  }

  stop(): Promise<string> {
    return this.soap("Stop", "");
  }

  seek(seconds: number): Promise<string> {
    return this.soap("Seek", `<Unit>REL_TIME</Unit><Target>${formatDuration(seconds)}</Target>`);
  }

  /** PLAYING / PAUSED_PLAYBACK / STOPPED / TRANSITIONING / NO_MEDIA_PRESENT, plus position and length in seconds. */
  async status(): Promise<{ state: string; position: number; duration: number }> {
    const transport = await this.soap("GetTransportInfo", "");
    const position = await this.soap("GetPositionInfo", "");
    return {
      state: tag(transport, "CurrentTransportState") ?? "UNKNOWN",
      position: parseDuration(tag(position, "RelTime")),
      duration: parseDuration(tag(position, "TrackDuration"))
    };
  }
}
