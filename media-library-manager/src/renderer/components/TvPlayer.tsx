import { useEffect, useRef, useState } from "react";
// Shaka Player plays the DASH stream and drives Widevine through the Castlabs Electron build's CDM.
import shaka from "shaka-player/dist/shaka-player.compiled.js";

/**
 * Full-screen live-TV playback for a Fizz channel on Windows. Shaka Player loads the DASH stream;
 * for a protected channel it wraps the Widevine licence request in Fizz's Vualto envelope (the
 * account token, the raw request bytes, and the content key id) and posts it to the licence proxy —
 * the same thing the Fizz app and the Android build do. Keys stay inside the platform's CDM.
 */
export default function TvPlayer({ channelId, channelName, onClose }: { channelId: number; channelName: string; onClose: () => void }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let player: any = null;
    let cancelled = false;

    (async () => {
      const result = await window.api.getTvStream(channelId);
      if (cancelled) return;
      if (!result.ok) {
        setError(result.error);
        setLoading(false);
        return;
      }
      const stream = result.value;
      const video = videoRef.current;
      if (!video) return;
      try {
        shaka.polyfill.installAll();
        player = new shaka.Player();
        await player.attach(video);
        player.addEventListener("error", (e: unknown) => {
          const detail = (e as { detail?: ShakaError }).detail;
          console.error("TV playback error", detail);
          void explainError(detail).then((msg) => setError(msg));
        });
        if (stream.drmToken) {
          player.configure({ drm: { servers: { "com.widevine.alpha": stream.licenseUrl } } });
          player.getNetworkingEngine()?.registerRequestFilter((type: number, request: any) => {
            if (type !== shaka.net.NetworkingEngine.RequestType.LICENSE) return;
            const challenge = Array.from(new Uint8Array(request.body as ArrayBuffer));
            request.headers["Content-Type"] = "application/json";
            request.body = new TextEncoder().encode(JSON.stringify({ token: stream.drmToken, drm_info: challenge, kid: stream.kid ?? "" }));
          });
        }
        await player.load(stream.url);
        if (cancelled) return;
        setLoading(false);
        void video.play().catch(() => undefined);
      } catch (e) {
        if (!cancelled) {
          console.error("TV playback error", e);
          setError(await explainError(e as ShakaError));
          setLoading(false);
        }
      }
    })();

    return () => {
      cancelled = true;
      player?.destroy().catch(() => undefined);
    };
  }, [channelId]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="tv-player">
      <video ref={videoRef} className="tv-player-video" controls autoPlay />
      <button className="btn tv-player-close" onClick={onClose}>
        Close
      </button>
      {loading && !error && <div className="tv-player-status">Opening {channelName}…</div>}
      {error && (
        <div className="tv-player-status tv-player-error">
          Can't play {channelName}.<br />
          {error}
        </div>
      )}
    </div>
  );
}

interface ShakaError {
  code?: number;
  category?: number;
  data?: unknown[];
}

/** Digs out the licence server's HTTP status + body from a (possibly nested) Shaka network error. */
function findHttp(err: ShakaError | undefined, depth = 0): { status?: number; body?: string } | null {
  if (!err || typeof err !== "object" || depth > 5) return null;
  // Shaka NETWORK (category 1) / BAD_HTTP_STATUS (code 1001): data = [uri, status, body, headers, type].
  if (err.category === 1 && err.code === 1001 && Array.isArray(err.data)) {
    const status = typeof err.data[1] === "number" ? (err.data[1] as number) : undefined;
    let body: string | undefined;
    const raw = err.data[2];
    try {
      if (raw instanceof ArrayBuffer) body = new TextDecoder().decode(raw);
      else if (raw instanceof Uint8Array) body = new TextDecoder().decode(raw);
      else if (typeof raw === "string") body = raw;
    } catch {
      /* ignore */
    }
    return { status, body: body?.trim().slice(0, 300) || undefined };
  }
  if (Array.isArray(err.data)) {
    for (const d of err.data) {
      const found = findHttp(d as ShakaError, depth + 1);
      if (found) return found;
    }
  }
  return null;
}

/**
 * Adds, for a DRM/EME failure, whether this build actually has a working Widevine CDM. If Widevine
 * isn't available at all, the Castlabs component didn't load (nothing to do with the channel); if it
 * is available, the channel is refusing this computer's software DRM (likely a hardware-DRM channel).
 */
async function explainError(detail?: ShakaError): Promise<string> {
  const base = describeError(detail);
  const code = detail?.code;
  if (!(code && code >= 6000 && code < 7000)) return base;
  try {
    const support = await shaka.Player.probeSupport();
    const wv = (support?.drm as Record<string, unknown> | undefined)?.["com.widevine.alpha"];
    if (!wv) return `${base}\nWidevine isn't available in this app on this PC (the Castlabs DRM component didn't load). It isn't the channel — no protected channel can play until that's fixed.`;
    const robustness = (wv as { videoRobustnessLevels?: string[] }).videoRobustnessLevels;
    const hw = Array.isArray(robustness) && robustness.some((r) => /HW/i.test(r));
    return `${base}\nWidevine IS available here (software/L3${hw ? " + some hardware levels" : ""}), so the channel is refusing this computer's DRM — it likely needs hardware DRM a PC can't provide. Cast it to a Google TV instead.`;
  } catch {
    return base;
  }
}

function describeError(detail?: ShakaError): string {
  const code = detail?.code;
  const http = findHttp(detail);
  if (http?.status) {
    const extra = http.body ? `\n${http.body}` : "";
    if (http.status === 403) {
      return `The channel's licence server refused this computer (HTTP 403). This usually means the channel allows only hardware DRM (Widevine L1), which phones and TVs have but a PC doesn't.${extra}`;
    }
    return `The channel's licence server returned HTTP ${http.status}.${extra}`;
  }
  // Shaka error codes 6000–6999 are DRM/EME.
  if (code && code >= 6000 && code < 7000) return `This computer couldn't get a licence for the channel (Widevine) — Shaka error ${code}.`;
  if (code === 1001 || code === 1002) return "Couldn't load the channel stream.";
  return code ? `Playback failed (error ${code}).` : "Playback failed.";
}
