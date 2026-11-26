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
          const code = (e as { detail?: { code?: number } }).detail?.code;
          setError(drmMessage(code));
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
          setError(drmMessage((e as { code?: number }).code));
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

function drmMessage(code?: number): string {
  // Shaka error codes 6000–6999 are DRM/EME.
  if (code && code >= 6000 && code < 7000) return "This computer couldn't get a licence for the channel (Widevine).";
  if (code === 1001 || code === 1002) return "Couldn't load the channel stream.";
  return "Playback failed.";
}
