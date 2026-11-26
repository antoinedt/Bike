import { IconButton } from "../components/Icon.js";
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import type { TvChannel, TvProgram, TvStatus } from "../../shared/types.js";

const DAY_MS = 24 * 3600_000;
const HALF_HOUR = 30 * 60_000;
const PX_PER_MIN = 4;
const CHANNEL_COL = 110;

function dayStart(offset: number): number {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() + offset);
  return d.getTime();
}

function dayLabel(offset: number): string {
  if (offset === 0) return "Today";
  if (offset === -1) return "Yesterday";
  if (offset === 1) return "Tomorrow";
  return new Date(dayStart(offset)).toLocaleDateString(undefined, { weekday: "short", day: "numeric" });
}

const time = (ms: number) => new Date(ms).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });

/** The TV tab: the Fizz TV guide for the channels in your plan. Asks you to sign in the first time. */
export default function TvPage() {
  const [status, setStatus] = useState<TvStatus | null>(null);
  const [signInError, setSignInError] = useState<string | null>(null);
  const [signingIn, setSigningIn] = useState(false);

  useEffect(() => {
    void window.api.getTvStatus().then(setStatus);
  }, []);

  const signIn = async () => {
    setSigningIn(true);
    setSignInError(null);
    const result = await window.api.tvSignIn();
    setSigningIn(false);
    if (result.ok) setStatus(result.value);
    else if (result.error !== "Sign-in was cancelled.") setSignInError(result.error);
  };

  const signedOut = useCallback((message: string | null) => {
    setSignInError(message);
    setStatus({ signedIn: false, email: null });
  }, []);

  if (!status) return <div className="empty-state">Loading…</div>;
  if (!status.signedIn) {
    return (
      <div className="tv-signin">
        <h1>Fizz TV</h1>
        <p>Sign in with your Fizz account to see the guide for the channels in your plan. You only need to do this once.</p>
        {signInError && <p className="error">{signInError}</p>}
        <button className="btn btn-primary" disabled={signingIn} onClick={() => void signIn()}>
          {signingIn ? "Signing in…" : "Sign in"}
        </button>
      </div>
    );
  }
  return <Guide email={status.email} onSignedOut={signedOut} />;
}

function Guide({ email, onSignedOut }: { email: string | null; onSignedOut: (message: string | null) => void }) {
  const [day, setDay] = useState(0);
  const [channels, setChannels] = useState<TvChannel[]>([]);
  const [programs, setPrograms] = useState<Map<number, TvProgram[]>>(new Map());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<{ program: TvProgram; channel: TvChannel } | null>(null);
  const [now, setNow] = useState(Date.now());
  const [jump, setJump] = useState(0);
  const [brokenLogos, setBrokenLogos] = useState<Set<number>>(new Set());
  const loadId = useRef(0);
  const scroller = useRef<HTMLDivElement>(null);
  const start = dayStart(day);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  const load = useCallback(
    async (offset: number, refresh = false) => {
      const id = ++loadId.current;
      const from = dayStart(offset);
      setLoading(true);
      setError(null);
      setPrograms(new Map());
      const list = await window.api.getTvChannels(refresh);
      if (id !== loadId.current) return;
      if (!list.ok) {
        setLoading(false);
        if (list.signInRequired) onSignedOut(list.error);
        else setError(list.error);
        return;
      }
      setChannels(list.value);
      // A few channels per request, a few requests at a time; rows fill in as they arrive.
      const chunks: TvChannel[][] = [];
      for (let i = 0; i < list.value.length; i += 10) chunks.push(list.value.slice(i, i + 10));
      let next = 0;
      let failure: string | null = null;
      const worker = async () => {
        while (next < chunks.length && id === loadId.current) {
          const chunk = chunks[next++];
          const result = await window.api.getTvGuide(chunk.map((c) => c.id), from, from + DAY_MS);
          if (id !== loadId.current) return;
          if (!result.ok) {
            if (result.signInRequired) onSignedOut(result.error);
            failure = result.error;
            return;
          }
          setPrograms((prev) => {
            const map = new Map(prev);
            for (const c of chunk) map.set(c.id, result.value.filter((p) => p.channelId === c.id));
            return map;
          });
        }
      };
      await Promise.all([worker(), worker(), worker(), worker()]);
      if (id !== loadId.current) return;
      setLoading(false);
      setError(failure);
    },
    [onSignedOut]
  );

  useEffect(() => {
    void load(day);
  }, [day, load]);

  // Start at "now" (with half an hour of context), or the same time of day on other days.
  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const sinceMidnight = Date.now() - dayStart(0) - HALF_HOUR;
    el.scrollLeft = Math.max(0, (sinceMidnight / 60_000) * PX_PER_MIN);
  }, [day, jump, channels.length > 0]); // eslint-disable-line react-hooks/exhaustive-deps

  const x = (ms: number) => ((ms - start) / 60_000) * PX_PER_MIN;
  const width = (DAY_MS / 60_000) * PX_PER_MIN;
  const showNow = now >= start && now < start + DAY_MS;

  return (
    <div className="tv-page">
      <div className="page-header">
        <div>
          <h1>My plan</h1>
          {email && <div className="tv-email">{email}</div>}
        </div>
        <div className="page-actions">
          <button
            className="btn btn-small"
            onClick={() => {
              setDay(0);
              setJump((j) => j + 1);
            }}
          >
            Now
          </button>
          <IconButton icon="refresh" label="Refresh" className="btn-small" onClick={() => void load(day, true)} />
          <IconButton
            icon="logout"
            label="Sign out"
            className="btn-small"
            onClick={() => {
              if (window.confirm("Sign out of Fizz? You'll need to sign in again to see the guide.")) {
                void window.api.tvSignOut().then(() => onSignedOut(null));
              }
            }}
          />
        </div>
      </div>
      <div className="tv-days">
        {[-1, 0, 1, 2, 3, 4, 5, 6].map((offset) => (
          <button key={offset} className={`tv-day${offset === day ? " active" : ""}`} onClick={() => setDay(offset)}>
            {dayLabel(offset)}
          </button>
        ))}
        {loading && <span className="tv-loading">Loading…</span>}
      </div>
      {error && (
        <p className="error">
          {error}{" "}
          <button className="btn btn-small" onClick={() => void load(day)}>
            Try again
          </button>
        </p>
      )}
      {!loading && !error && channels.length === 0 ? (
        <div className="empty-state">No channels in your plan.</div>
      ) : (
        <div className="tv-grid" ref={scroller}>
          <div className="tv-grid-inner" style={{ width: CHANNEL_COL + width }}>
            <div className="tv-row tv-header">
              <div className="tv-channel tv-corner" style={{ width: CHANNEL_COL }} />
              <div className="tv-timeline" style={{ width }}>
                {Array.from({ length: 48 }, (_, i) => (
                  <span key={i} className="tv-tick" style={{ left: i * 30 * PX_PER_MIN }}>
                    {time(start + i * HALF_HOUR)}
                  </span>
                ))}
                {showNow && <div className="tv-now" style={{ left: x(now) }} />}
              </div>
            </div>
            {channels.map((channel) => {
              const list = programs.get(channel.id);
              return (
                <div className="tv-row" key={channel.id}>
                  <div className="tv-channel" style={{ width: CHANNEL_COL }} title={channel.name}>
                    {channel.logoUrl && !brokenLogos.has(channel.id) ? (
                      <img src={channel.logoUrl} alt={channel.name} onError={() => setBrokenLogos((b) => new Set(b).add(channel.id))} />
                    ) : (
                      <span className="tv-channel-name">{channel.name}</span>
                    )}
                    {channel.number != null && <span className="tv-channel-number">{channel.number}</span>}
                  </div>
                  <div className="tv-timeline" style={{ width }}>
                    {list === undefined && <div className="tv-placeholder" />}
                    {list?.length === 0 && <span className="tv-none">No listings</span>}
                    {list?.map((p) => {
                      const left = Math.max(0, x(p.start));
                      const right = Math.min(width, x(p.end));
                      if (right <= left) return null;
                      const state = p.end <= now ? " past" : p.start <= now ? " live" : "";
                      return (
                        <button
                          key={p.key}
                          className={`tv-program${state}`}
                          style={{ left, width: right - left }}
                          title={`${p.title} · ${time(p.start)} – ${time(p.end)}`}
                          onClick={() => setSelected({ program: p, channel })}
                        >
                          <span className="tv-program-title">{p.title}</span>
                          <span className="tv-program-sub">{p.episode ?? `${time(p.start)} – ${time(p.end)}`}</span>
                        </button>
                      );
                    })}
                    {showNow && <div className="tv-now" style={{ left: x(now) }} />}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}
      {selected && (
        <div className="player-overlay" onClick={() => setSelected(null)}>
          <div className="confirm-dialog tv-details" onClick={(e) => e.stopPropagation()}>
            {selected.program.imageUrl && <img className="tv-details-image" src={selected.program.imageUrl} alt="" />}
            <h2>{selected.program.title}</h2>
            {selected.program.episode && <div className="tv-details-episode">{selected.program.episode}</div>}
            <div className="tv-details-meta">
              {selected.channel.number != null ? `${selected.channel.number} · ` : ""}
              {selected.channel.name} · {new Date(selected.program.start).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" })}{" "}
              {time(selected.program.start)} – {time(selected.program.end)}
            </div>
            {selected.program.description && <p>{selected.program.description}</p>}
            <div className="page-actions" style={{ justifyContent: "flex-end" }}>
              <button className="btn" onClick={() => setSelected(null)}>
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
