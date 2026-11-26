import type { CastState, CastTarget, LocalFolder, MediaItem, NetworkShare } from "../../shared/types.js";
import { relPathFromMediaPath } from "../network/smbClient.js";
import { discoverCast } from "./castDiscovery.js";
import { CastSession, type CastMediaStatus } from "./castv2.js";
import { DlnaRenderer, discoverDlna } from "./dlna.js";
import { publish, unpublish, type MediaSource } from "./mediaServer.js";

// Casting a library item to a TV: Google TV / Chromecast over Google Cast, or a DLNA renderer such
// as a Samsung TV. One cast at a time; the state goes to `onState` (the remote-control bar).

type Settings = { localFolders: LocalFolder[]; networkShares: NetworkShare[] };

export class CastController {
  private session: CastSession | null = null;
  private dlna: DlnaRenderer | null = null;
  private token: string | null = null;
  private poll: NodeJS.Timeout | null = null;
  private state: CastState | null = null;
  private devices = new Map<string, CastTarget>();

  constructor(private settings: () => Settings, private onState: (state: CastState | null) => void) {}

  /** Google Cast and DLNA devices on the network (takes ~3 s). */
  async discover(timeoutMs = 3000): Promise<CastTarget[]> {
    const [cast, dlna] = await Promise.all([
      discoverCast(timeoutMs).catch(() => []),
      discoverDlna(timeoutMs).catch(() => [])
    ]);
    const targets: CastTarget[] = [
      ...cast.map((d) => ({ id: `cast:${d.id}`, name: d.name, kind: "chromecast" as const, host: d.host, port: d.port })),
      ...dlna.map((d) => ({ id: `dlna:${d.id}`, name: d.name, kind: "dlna" as const, host: d.host, controlUrl: d.controlUrl }))
    ];
    for (const t of targets) this.devices.set(t.id, t);
    return targets.sort((a, b) => a.name.localeCompare(b.name));
  }

  current(): CastState | null {
    return this.state;
  }

  private set(state: CastState | null): void {
    this.state = state;
    this.onState(state);
  }

  private source(item: MediaItem): MediaSource {
    if (item.source.type === "local") return { kind: "local", path: item.path };
    const shareId = item.source.shareId;
    const share = this.settings().networkShares.find((s) => s.id === shareId);
    if (!share) throw new Error("The network share for this item is no longer configured.");
    return { kind: "smb", share, relPath: relPathFromMediaPath(share, item.path) };
  }

  async start(targetId: string, item: MediaItem): Promise<void> {
    const target = this.devices.get(targetId);
    if (!target) throw new Error("That TV isn't available any more. Search again.");
    await this.stop(false);
    const title = item.title || item.name;
    this.set({ deviceName: target.name, deviceKind: target.kind, itemName: title, state: "loading", position: 0, duration: item.durationSeconds ?? 0 });
    try {
      const published = await publish(this.source(item), item.name, item.extension, target.host);
      this.token = published.token;
      if (target.kind === "chromecast") {
        const session = await CastSession.connect(target.host, target.port);
        this.session = session;
        session.on("status", (s: CastMediaStatus) => this.fromCast(s));
        session.on("close", () => {
          if (this.session === session) void this.stop(true);
        });
        await session.load(published.url, published.contentType, title);
        this.poll = setInterval(() => session.refresh(), 1000);
      } else {
        const renderer = new DlnaRenderer({ id: target.id, name: target.name, host: target.host, controlUrl: target.controlUrl! });
        this.dlna = renderer;
        await renderer.load(published.url, published.contentType, title, item.kind === "audio" ? "audio" : item.kind === "image" ? "image" : "video");
        this.poll = setInterval(() => void this.pollDlna(), 1000);
      }
    } catch (err) {
      await this.stop(false);
      this.set({ deviceName: target.name, deviceKind: target.kind, itemName: title, state: "error", position: 0, duration: 0, error: (err as Error).message });
    }
  }

  private fromCast(s: CastMediaStatus): void {
    if (!this.state) return;
    const map: Record<string, CastState["state"]> = { PLAYING: "playing", PAUSED: "paused", BUFFERING: "buffering", IDLE: "stopped" };
    const state = map[s.playerState] ?? this.state.state;
    if (state === "stopped" && this.state.state === "loading" && !s.idleReason) return; // before the first frame
    this.set({ ...this.state, state, position: s.currentTime, duration: s.duration ?? this.state.duration });
    if (state === "stopped") void this.stop(false, true);
  }

  private async pollDlna(): Promise<void> {
    if (!this.dlna || !this.state) return;
    try {
      const s = await this.dlna.status();
      const map: Record<string, CastState["state"]> = {
        PLAYING: "playing", PAUSED_PLAYBACK: "paused", TRANSITIONING: "buffering", STOPPED: "stopped", NO_MEDIA_PRESENT: "stopped"
      };
      const state = map[s.state] ?? this.state.state;
      if (state === "stopped" && this.state.state === "loading") return;
      this.set({ ...this.state, state, position: s.position, duration: s.duration || this.state.duration });
      if (state === "stopped") void this.stop(false, true);
    } catch {
      // the TV may be busy; try again next tick
    }
  }

  async control(action: "play" | "pause" | "seek", value?: number): Promise<void> {
    if (this.session) {
      if (action === "play") this.session.play();
      else if (action === "pause") this.session.pause();
      else this.session.seek(value ?? 0);
    } else if (this.dlna) {
      if (action === "play") await this.dlna.play();
      else if (action === "pause") await this.dlna.pause();
      else await this.dlna.seek(value ?? 0);
    }
    if (this.state && action === "seek") this.set({ ...this.state, position: value ?? 0 });
  }

  /** Stops casting. `stopTv` also stops playback on the TV; `keepState` leaves the final state shown. */
  async stop(stopTv = true, keepState = false): Promise<void> {
    if (this.poll) clearInterval(this.poll);
    this.poll = null;
    const session = this.session;
    const dlna = this.dlna;
    this.session = null;
    this.dlna = null;
    if (stopTv) {
      await session?.stop().catch(() => undefined);
      await dlna?.stop().catch(() => undefined);
    } else if (session && !keepState) {
      await session.stop().catch(() => undefined);
    }
    if (this.token) unpublish(this.token);
    this.token = null;
    if (!keepState) this.set(null);
  }
}
