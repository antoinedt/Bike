import { EventEmitter } from "node:events";
import tls from "node:tls";

// A small Google Cast (CASTV2) sender: enough to start the Default Media Receiver on a Google TV /
// Chromecast, play a URL, and control it. Messages are protobuf `CastMessage`s over TLS on port
// 8009, each preceded by a 4-byte big-endian length. The payloads are JSON.

const DEFAULT_MEDIA_RECEIVER = "CC1AD845";
// Fizz's own registered Cast receiver: it does the Widevine playback for a live channel on the TV,
// given the stream URL and the DRM token/type/content-id in the media metadata (as the Fizz app does).
const FIZZ_RECEIVER = "559B0340";
const NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
const NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
const NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
const NS_MEDIA = "urn:x-cast:com.google.cast.media";
const SENDER = "sender-mlm";

export interface CastMessage {
  source: string;
  destination: string;
  namespace: string;
  data: string;
}

function varint(n: number): Buffer {
  const bytes: number[] = [];
  do {
    let b = n & 0x7f;
    n >>>= 7;
    if (n) b |= 0x80;
    bytes.push(b);
  } while (n);
  return Buffer.from(bytes);
}

function stringField(tag: number, value: string): Buffer {
  const data = Buffer.from(value, "utf8");
  return Buffer.concat([Buffer.from([tag]), varint(data.length), data]);
}

/** CastMessage { protocol_version=1: 0, source_id=2, destination_id=3, namespace=4, payload_type=5: STRING, payload_utf8=6 }. */
export function encodeCastMessage(m: CastMessage): Buffer {
  const body = Buffer.concat([
    Buffer.from([0x08, 0x00]),
    stringField(0x12, m.source),
    stringField(0x1a, m.destination),
    stringField(0x22, m.namespace),
    Buffer.from([0x28, 0x00]),
    stringField(0x32, m.data)
  ]);
  const len = Buffer.alloc(4);
  len.writeUInt32BE(body.length);
  return Buffer.concat([len, body]);
}

export function decodeCastMessage(body: Buffer): CastMessage {
  const m: CastMessage = { source: "", destination: "", namespace: "", data: "" };
  let pos = 0;
  const readVarint = () => {
    let result = 0;
    let shift = 0;
    for (;;) {
      const b = body[pos++];
      result += (b & 0x7f) * 2 ** shift;
      if (!(b & 0x80)) return result;
      shift += 7;
    }
  };
  while (pos < body.length) {
    const key = readVarint();
    const field = key >>> 3;
    const wire = key & 7;
    if (wire === 0) {
      readVarint();
      continue;
    }
    if (wire !== 2) throw new Error("Unexpected Cast message encoding.");
    const len = readVarint();
    const value = body.subarray(pos, pos + len);
    pos += len;
    if (field === 2) m.source = value.toString("utf8");
    else if (field === 3) m.destination = value.toString("utf8");
    else if (field === 4) m.namespace = value.toString("utf8");
    else if (field === 6) m.data = value.toString("utf8");
  }
  return m;
}

export interface CastMediaStatus {
  playerState: "IDLE" | "PLAYING" | "PAUSED" | "BUFFERING" | string;
  currentTime: number;
  duration?: number;
  idleReason?: string;
}

/** A live Fizz channel to cast: the stream URL and the DRM details the Fizz receiver needs. */
export interface CastLiveStream {
  url: string;
  /** The content type sent in the LOAD (Fizz sends video/mp4; the receiver picks the parser). */
  contentType: string;
  /** 2 = DASH, 4 = HLS. */
  mediaFormatId: number;
  drmToken: string | null;
  drmLicenseUrl: string;
  drmType: number;
  drmContentId: string | null;
}

/** One connection to a Cast device. Emits "status" (CastMediaStatus) and "close". */
export class CastSession extends EventEmitter {
  private socket!: tls.TLSSocket;
  private buffer = Buffer.alloc(0);
  private requestId = 1;
  private waiters = new Map<number, (payload: Record<string, unknown>) => void>();
  private heartbeat?: NodeJS.Timeout;
  private transportId?: string;
  private sessionId?: string;
  private mediaSessionId?: number;
  private closed = false;

  static async connect(host: string, port = 8009, timeoutMs = 8000): Promise<CastSession> {
    const session = new CastSession();
    await new Promise<void>((resolve, reject) => {
      // Cast devices present a self-signed certificate.
      const socket = tls.connect({ host, port, rejectUnauthorized: false, timeout: timeoutMs }, () => resolve());
      socket.once("error", reject);
      socket.once("timeout", () => reject(new Error(`No answer from ${host}.`)));
      session.socket = socket;
    });
    session.socket.setTimeout(0);
    session.socket.on("data", (d) => session.onData(d));
    session.socket.on("close", () => session.shutdown());
    session.socket.on("error", () => session.shutdown());
    session.send(NS_CONNECTION, "receiver-0", { type: "CONNECT" });
    session.heartbeat = setInterval(() => session.send(NS_HEARTBEAT, "receiver-0", { type: "PING" }), 5000);
    return session;
  }

  private send(namespace: string, destination: string, payload: Record<string, unknown>): void {
    if (this.closed) return;
    this.socket.write(encodeCastMessage({ source: SENDER, destination, namespace, data: JSON.stringify(payload) }));
  }

  private request(namespace: string, destination: string, payload: Record<string, unknown>, timeoutMs = 15000): Promise<Record<string, unknown>> {
    const id = this.requestId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters.delete(id);
        reject(new Error("The TV didn't answer."));
      }, timeoutMs);
      this.waiters.set(id, (reply) => {
        clearTimeout(timer);
        resolve(reply);
      });
      this.send(namespace, destination, { ...payload, requestId: id });
    });
  }

  private onData(data: Buffer): void {
    this.buffer = Buffer.concat([this.buffer, data]);
    while (this.buffer.length >= 4) {
      const len = this.buffer.readUInt32BE(0);
      if (this.buffer.length < 4 + len) return;
      const message = decodeCastMessage(this.buffer.subarray(4, 4 + len));
      this.buffer = this.buffer.subarray(4 + len);
      let payload: Record<string, unknown>;
      try {
        payload = JSON.parse(message.data);
      } catch {
        continue;
      }
      if (message.namespace === NS_HEARTBEAT && payload.type === "PING") {
        this.send(NS_HEARTBEAT, message.source, { type: "PONG" });
        continue;
      }
      if (message.namespace === NS_CONNECTION && payload.type === "CLOSE") {
        if (message.source === this.transportId) this.emit("status", { playerState: "IDLE", currentTime: 0, idleReason: "FINISHED" });
        continue;
      }
      if (message.namespace === NS_MEDIA && payload.type === "MEDIA_STATUS") {
        const status = (payload.status as Array<Record<string, unknown>> | undefined)?.[0];
        if (status) {
          this.mediaSessionId = (status.mediaSessionId as number) ?? this.mediaSessionId;
          const media = status.media as { duration?: number } | undefined;
          this.emit("status", {
            playerState: String(status.playerState ?? "IDLE"),
            currentTime: Number(status.currentTime ?? 0),
            duration: media?.duration,
            idleReason: status.idleReason as string | undefined
          } satisfies CastMediaStatus);
        }
      }
      const id = payload.requestId as number | undefined;
      if (id && this.waiters.has(id)) {
        const waiter = this.waiters.get(id)!;
        this.waiters.delete(id);
        waiter(payload);
      }
    }
  }

  /** Launches `appId` on the TV (or joins it if it's already running) and connects to its transport. */
  private async launchApp(appId: string): Promise<void> {
    const status = await this.request(NS_RECEIVER, "receiver-0", { type: "LAUNCH", appId });
    if (status.type === "LAUNCH_ERROR") throw new Error(`The TV couldn't start its player (${status.reason ?? "unknown reason"}).`);
    const apps = ((status.status as { applications?: Array<Record<string, string>> })?.applications ?? []);
    const app = apps.find((a) => a.appId === appId);
    if (!app) throw new Error("The TV didn't start its player.");
    this.transportId = app.transportId;
    this.sessionId = app.sessionId;
    this.send(NS_CONNECTION, this.transportId, { type: "CONNECT" });
  }

  private async sendLoad(media: Record<string, unknown>, failMessage: string): Promise<void> {
    const reply = await this.request(NS_MEDIA, this.transportId as string, { type: "LOAD", autoplay: true, currentTime: 0, media });
    if (reply.type === "LOAD_FAILED" || reply.type === "INVALID_REQUEST") throw new Error(failMessage);
  }

  /** Starts the Default Media Receiver (or joins it if it's already running) and plays `url`. */
  async load(url: string, contentType: string, title: string): Promise<void> {
    await this.launchApp(DEFAULT_MEDIA_RECEIVER);
    await this.sendLoad(
      { contentId: url, contentType, streamType: "BUFFERED", metadata: { metadataType: 0, title } },
      "The TV couldn't play this file (it may not support this format)."
    );
  }

  /**
   * Casts a live Fizz channel: launches Fizz's own receiver (which does the Widevine playback on the
   * TV) and hands it the stream URL plus the DRM token/type/content-id in the media metadata — the
   * same keys the Fizz app sends. The Default Media Receiver can't do the Vualto Widevine licence.
   */
  async loadLive(stream: CastLiveStream, title: string): Promise<void> {
    await this.launchApp(FIZZ_RECEIVER);
    const metadata: Record<string, unknown> = {
      metadataType: 1,
      title,
      key_title: title,
      channel_name: title,
      content_type: "4",
      media_format_id: stream.mediaFormatId
    };
    if (stream.drmToken) {
      metadata.drm_token = stream.drmToken;
      metadata.drm_license_url = stream.drmLicenseUrl;
      metadata.drm_type = stream.drmType;
      if (stream.drmContentId) metadata.contentId = stream.drmContentId;
    }
    await this.sendLoad(
      { contentId: stream.url, contentUrl: stream.url, contentType: stream.contentType, streamType: "LIVE", metadata },
      "The TV couldn't start the channel."
    );
  }

  private media(type: string, extra: Record<string, unknown> = {}): void {
    if (!this.transportId || this.mediaSessionId === undefined) return;
    void this.request(NS_MEDIA, this.transportId, { type, mediaSessionId: this.mediaSessionId, ...extra }).catch(() => undefined);
  }

  play(): void {
    this.media("PLAY");
  }

  pause(): void {
    this.media("PAUSE");
  }

  seek(seconds: number): void {
    this.media("SEEK", { currentTime: seconds });
  }

  refresh(): void {
    if (this.transportId) void this.request(NS_MEDIA, this.transportId, { type: "GET_STATUS" }).catch(() => undefined);
  }

  /** Stops playback on the TV and disconnects. */
  async stop(): Promise<void> {
    if (this.sessionId) {
      await this.request(NS_RECEIVER, "receiver-0", { type: "STOP", sessionId: this.sessionId }, 3000).catch(() => undefined);
    }
    this.shutdown();
  }

  private shutdown(): void {
    if (this.closed) return;
    this.closed = true;
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.socket?.destroy();
    this.emit("close");
  }
}
