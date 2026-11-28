import { useEffect, useRef, useState } from "react";
import type { MediaItem } from "../../shared/types.js";

function formatPosition(ms: number): string {
  const s = Math.floor(ms / 1000);
  const pad = (n: number) => String(n).padStart(2, "0");
  return s >= 3600 ? `${Math.floor(s / 3600)}:${pad(Math.floor(s / 60) % 60)}:${pad(s % 60)}` : `${Math.floor(s / 60)}:${pad(s % 60)}`;
}

/**
 * The in-app player for library video/audio (on this PC or a share). Picks up where it was left
 * off — here or on another device, through the file's shared metadata — and saves the position
 * every 10 s and on close.
 */
export default function StreamPlayer({ item, url, onClose }: { item: MediaItem; url: string; onClose: () => void }) {
  const mediaRef = useRef<HTMLVideoElement & HTMLAudioElement>(null);
  const [resumedAt, setResumedAt] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  function save(ended = false) {
    const m = mediaRef.current;
    if (!m || !isFinite(m.currentTime)) return;
    const duration = isFinite(m.duration) ? m.duration * 1000 : 0;
    void window.api.saveResume(item.id, m.currentTime * 1000, duration, ended || m.ended).catch(() => {});
  }

  useEffect(() => {
    let cancelled = false;
    void window.api.getResume(item.id).then((at) => {
      const m = mediaRef.current;
      if (cancelled || !m || at == null) return;
      const seek = () => {
        m.currentTime = at / 1000;
        setResumedAt(at);
      };
      if (m.readyState >= 1) seek();
      else m.addEventListener("loadedmetadata", seek, { once: true });
    });
    const timer = setInterval(() => {
      if (mediaRef.current && !mediaRef.current.paused) save();
    }, 10_000);
    return () => {
      cancelled = true;
      clearInterval(timer);
      save();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.id]);

  useEffect(() => {
    if (resumedAt == null) return;
    const t = setTimeout(() => setResumedAt(null), 8000);
    return () => clearTimeout(t);
  }, [resumedAt]);

  const common = {
    ref: mediaRef,
    className: "player-media",
    src: url,
    controls: true,
    autoPlay: true,
    onPause: () => save(),
    onEnded: () => save(true),
    onError: () =>
      setError("This file can't be played here (its format isn't supported by the built-in player).")
  };

  return (
    <div className="player-overlay" onClick={onClose}>
      <div className="player-panel" onClick={(e) => e.stopPropagation()}>
        <div className="player-header">
          <span className="player-title">{item.title ?? item.name}</span>
          <button className="btn btn-small" onClick={onClose}>
            Close
          </button>
        </div>
        {item.kind === "audio" ? <audio {...common} /> : <video {...common} />}
        {resumedAt != null && (
          <div className="resume-note">
            Resumed at {formatPosition(resumedAt)}
            <button
              className="link-button"
              onClick={() => {
                if (mediaRef.current) mediaRef.current.currentTime = 0;
                setResumedAt(null);
                void window.api.saveResume(item.id, 0, 0, true);
              }}
            >
              Start over
            </button>
          </div>
        )}
        {error && (
          <p className="error">
            {error}{" "}
            {item.source.type === "local" && (
              <button className="link-button" onClick={() => void window.api.openItem(item).then(onClose)}>
                Open in your default player
              </button>
            )}
          </p>
        )}
      </div>
    </div>
  );
}
