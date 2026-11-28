import { useMemo, useRef } from "react";
import type { MediaItem } from "../../shared/types.js";

export interface ResumeEntry {
  itemId: string;
  positionMs: number;
  durationMs: number;
  at: number;
}

const EPISODE = /^(.*?)[\s._-]+S(\d{1,2})[\s._-]?E(\d{1,3})/i;

/** "The.Bear.S05E03.1080p" -> { show: "The Bear", season: 5, episode: 3 }; null for anything else. */
export function episodeInfo(item: MediaItem): { show: string; season: number; episode: number } | null {
  const m = EPISODE.exec(item.name);
  if (!m) return null;
  const show = m[1].replace(/[._]+/g, " ").replace(/\s+/g, " ").trim();
  return show ? { show, season: Number(m[2]), episode: Number(m[3]) } : null;
}

/** "Dune.Part.Two.2024.2160p.WEB-DL.mkv" -> { title: "Dune Part Two", year: "2024" }. */
function cleanName(item: MediaItem): { title: string; year?: string } {
  const base = item.name.replace(/\.[^.]+$/, "").replace(/[._]+/g, " ");
  const year = /\b(19\d{2}|20\d{2})\b/.exec(base);
  const cut = base.search(/[([]?\b(19\d{2}|20\d{2}|2160p|1080p|720p|480p|x26[45]|h ?26[45]|hevc|web|webrip|web-dl|bluray|brrip|hdtv)\b/i);
  const title = (cut > 0 ? base.slice(0, cut) : base).replace(/[\s([-]+$/, "").trim();
  // A year at the very start ("1967 Astérix le Gaulois") is the year, not part of the title.
  const clean = (title || base.trim()).replace(/^(19|20)\d{2}\s+(?=\S)/, "");
  return { title: clean || title || base.trim(), year: year?.[1] };
}

function displayTitle(item: MediaItem): string {
  const ep = episodeInfo(item);
  if (ep) return `${ep.show} · S${String(ep.season).padStart(2, "0")}E${String(ep.episode).padStart(2, "0")}`;
  return item.title || cleanName(item).title;
}

function displayYear(item: MediaItem): string | undefined {
  return item.year || (episodeInfo(item) ? undefined : cleanName(item).year);
}

/** A stable, muted color pair for items without artwork, so the wall isn't uniformly grey. */
function placeholder(seed: string): string {
  let h = 0;
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) >>> 0;
  const hue = h % 360;
  return `linear-gradient(160deg, hsl(${hue} 32% 30%), hsl(${(hue + 30) % 360} 40% 9%))`;
}

function formatLeft(entry: ResumeEntry): string {
  if (entry.durationMs > 0) {
    const left = Math.max(0, Math.round((entry.durationMs - entry.positionMs) / 60000));
    return left >= 60 ? `${Math.floor(left / 60)} h ${left % 60} min left` : `${left} min left`;
  }
  const at = Math.round(entry.positionMs / 60000);
  return `Stopped at ${at} min`;
}

function Poster({
  item,
  label,
  sub,
  progress,
  group,
  onClick
}: {
  item: MediaItem;
  label: string;
  sub?: string;
  progress?: number;
  /** A show (opens its episodes) rather than a file (plays): drawn as a stack with a badge. */
  group?: number;
  onClick: () => void;
}) {
  return (
    <button className={`cine-poster${group != null ? " is-group" : ""}`} onClick={onClick} title={label}>
      <div className="cine-poster-art" style={item.artworkUrl ? undefined : { background: placeholder(label) }}>
        <span className="cine-badge" aria-hidden="true">{group != null ? `📂 ${group}` : "▶"}</span>
        {item.artworkUrl && <img src={item.artworkUrl} alt="" loading="lazy" />}
        {!item.artworkUrl && <span className="cine-poster-fallback">{label}</span>}
        {progress != null && (
          <span className="cine-progress" aria-label={`${Math.round(progress * 100)}% watched`}>
            <i style={{ width: `${Math.min(100, Math.max(3, progress * 100))}%` }} />
          </span>
        )}
      </div>
      {item.artworkUrl && <span className="cine-poster-label">{label}</span>}
      {sub && <span className="cine-poster-sub">{sub}</span>}
    </button>
  );
}

function Row({ title, children }: { title: string; children: React.ReactNode }) {
  const track = useRef<HTMLDivElement>(null);
  const scroll = (dir: number) => track.current?.scrollBy({ left: dir * track.current.clientWidth * 0.85, behavior: "smooth" });
  return (
    <section className="cine-row">
      <div className="cine-row-head">
        <h2>{title}</h2>
        <div className="cine-row-arrows">
          <button className="cine-arrow" onClick={() => scroll(-1)} aria-label={`Scroll ${title} left`}>‹</button>
          <button className="cine-arrow" onClick={() => scroll(1)} aria-label={`Scroll ${title} right`}>›</button>
        </div>
      </div>
      <div className="cine-row-track" ref={track}>
        {children}
      </div>
    </section>
  );
}

/**
 * The Library's home in the Cinema style: a featured title (what you were last watching, or the
 * newest arrival) and rows of posters — Continue watching, Recently added, Shows, Movies, Music.
 * A poster opens its details (play, cast, copy, edit); the hero's button plays right away.
 */
export default function CinemaHome({
  items,
  resume,
  onPlay,
  onDetails,
  onShowSearch,
  onBrowseFolders
}: {
  items: MediaItem[];
  resume: ResumeEntry[];
  onPlay: (item: MediaItem) => void;
  onDetails: (item: MediaItem) => void;
  onShowSearch: (query: string) => void;
  onBrowseFolders: () => void;
}) {
  const rows = useMemo(() => {
    const byId = new Map(items.map((i) => [i.id, i]));
    // One entry per file: the same file in two places (same name and size) shows once, the local copy.
    const fileKey = (i: MediaItem) => `${i.name.toLowerCase()}|${i.sizeBytes}`;
    const distinct = new Map<string, MediaItem>();
    for (const i of items) {
      const prev = distinct.get(fileKey(i));
      if (!prev || (prev.source.type !== "local" && i.source.type === "local")) distinct.set(fileKey(i), i);
    }
    const files = [...distinct.values()];
    const seen = new Set<string>();
    const continuing = resume
      .map((r) => ({ entry: r, item: byId.get(r.itemId) }))
      .filter((x): x is { entry: ResumeEntry; item: MediaItem } => !!x.item)
      .filter((x) => !seen.has(fileKey(x.item)) && !!seen.add(fileKey(x.item)));
    const videos = files.filter((i) => i.kind === "video");
    const recent = [...videos].sort((a, b) => b.modifiedAt - a.modifiedAt).slice(0, 24);

    // One tile per show: its newest episode stands in for it.
    const shows = new Map<string, { item: MediaItem; count: number }>();
    const movies: MediaItem[] = [];
    for (const v of videos) {
      const ep = episodeInfo(v);
      if (!ep) {
        movies.push(v);
        continue;
      }
      const key = ep.show.toLowerCase();
      const prev = shows.get(key);
      if (!prev) shows.set(key, { item: v, count: 1 });
      else {
        prev.count++;
        if (v.modifiedAt > prev.item.modifiedAt) prev.item = v;
      }
    }
    movies.sort((a, b) => displayTitle(a).localeCompare(displayTitle(b)));
    const music = files.filter((i) => i.kind === "audio").sort((a, b) => b.modifiedAt - a.modifiedAt).slice(0, 24);
    return {
      continuing,
      recent,
      shows: [...shows.values()].sort((a, b) => b.item.modifiedAt - a.item.modifiedAt),
      movies,
      music
    };
  }, [items, resume]);

  const featured = rows.continuing[0]?.item ?? rows.recent.find((i) => i.artworkUrl) ?? rows.recent[0];
  const featuredResume = rows.continuing[0];

  if (!featured) {
    return (
      <div className="cine-empty">
        <p>No videos yet. Add a folder or a network share in Settings; it's scanned automatically.</p>
        <button className="btn" onClick={onBrowseFolders}>Browse folders</button>
      </div>
    );
  }

  return (
    <div className="cine-home">
      <section className="cine-hero" style={{ ["--hero-bg" as string]: featured.artworkUrl ? `url("${featured.artworkUrl}")` : placeholder(displayTitle(featured)) }}>
        <div className="cine-hero-backdrop" aria-hidden="true" />
        <div className="cine-hero-body">
          <span className="cine-eyebrow">
            {featuredResume ? `Continue · ${formatLeft(featuredResume.entry)}` : "Recently added"}
          </span>
          <h1>{displayTitle(featured)}</h1>
          <p className="cine-hero-meta">
            {[displayYear(featured), featured.durationSeconds ? `${Math.round(featured.durationSeconds / 60)} min` : null, featured.extension.toUpperCase()]
              .filter(Boolean)
              .join(" · ")}
          </p>
          <div className="cine-hero-actions">
            <button className="cine-btn-play" onClick={() => onPlay(featured)}>
              ▶ {featuredResume ? "Resume" : "Play"}
            </button>
            <button className="cine-btn-ghost" onClick={() => onDetails(featured)}>
              Details
            </button>
          </div>
        </div>
      </section>

      {rows.continuing.length > 0 && (
        <Row title="Continue watching">
          {rows.continuing.map(({ entry, item }) => (
            <Poster
              key={item.id}
              item={item}
              label={displayTitle(item)}
              sub={formatLeft(entry)}
              progress={entry.durationMs > 0 ? entry.positionMs / entry.durationMs : 0.5}
              onClick={() => onPlay(item)}
            />
          ))}
        </Row>
      )}
      {rows.recent.length > 0 && (
        <Row title="Recently added">
          {rows.recent.map((item) => (
            <Poster key={item.id} item={item} label={displayTitle(item)} sub={displayYear(item)} onClick={() => onDetails(item)} />
          ))}
        </Row>
      )}
      {rows.shows.length > 0 && (
        <Row title="Shows">
          {rows.shows.map(({ item, count }) => {
            const show = episodeInfo(item)!.show;
            return (
              <Poster
                key={show}
                item={item}
                label={show}
                sub={`Show · ${count} episode${count === 1 ? "" : "s"}`}
                group={count}
                onClick={() => onShowSearch(show)}
              />
            );
          })}
        </Row>
      )}
      {rows.movies.length > 0 && (
        <Row title="Movies">
          {rows.movies.map((item) => (
            <Poster key={item.id} item={item} label={displayTitle(item)} sub={displayYear(item)} onClick={() => onDetails(item)} />
          ))}
        </Row>
      )}
      {rows.music.length > 0 && (
        <Row title="Music">
          {rows.music.map((item) => (
            <Poster key={item.id} item={item} label={displayTitle(item)} onClick={() => onPlay(item)} />
          ))}
        </Row>
      )}
    </div>
  );
}
