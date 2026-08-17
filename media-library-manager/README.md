# Media Library Manager

A cross-platform (Windows/macOS/Linux) desktop app for managing a media library from three
sources:

- **Local device** — folders on this machine, recursively scanned for video/audio/image files.
- **Local network** — SMB/Windows shares (e.g. a NAS), browsed and **streamed live** (seekable,
  no full download first) via an in-app player.
- **Search** — a user-configured search against a site you have the legal right to use, with
  results handed off to your OS's default torrent app (magnet links or `.torrent` files).

Built with Electron + React + TypeScript.

## Legal / scope note

This app does not embed a torrent client, tracker, or any indexer of its own, and ships with no
sites pre-configured. The **Search** feature only works after you add a site profile yourself in
**Settings → Search sites**, and it only fetches sites you explicitly configure. Downloads are
handed off to whatever application your OS already has registered for `magnet:` links / `.torrent`
files — the app never downloads torrent content itself. Only use this against sites and content
you're legally permitted to search and download.

## Project layout

```
src/
├── main/                 Electron main process (Node)
│   ├── index.ts           App entry: window creation
│   ├── preload.cts        contextBridge API exposed to the renderer (CommonJS on purpose)
│   ├── ipc.ts              ipcMain handlers wiring the renderer to everything below
│   ├── settings.ts         electron-store-backed config (folders, shares, site profiles)
│   ├── db.ts                lowdb-backed media index cache
│   ├── library/scanner.ts   recursive local filesystem scan
│   ├── network/
│   │   ├── smbClient.ts      SMB2 share listing (readdir-based)
│   │   ├── smbRawClient.ts   raw offset/length SMB2 READ requests, for streaming
│   │   └── streamServer.ts   loopback-only HTTP Range server relaying smbRawClient → <video>/<audio>
│   ├── search/
│   │   ├── siteSearch.ts     cheerio-based generic scraper driven by SiteProfile selectors
│   │   └── headlessFetch.ts  hidden BrowserWindow fetch for JS-rendered sites (opt-in per profile)
│   ├── artwork/artworkFetcher.ts  title-guessing + iTunes Search API cover/poster lookup
│   └── torrent/handoff.ts   magnet: → shell.openExternal; .torrent → download + shell.openPath
├── renderer/              React UI (Vite)
│   ├── App.tsx, components/ (incl. StreamPlayer), pages/
└── shared/                Types shared between main and renderer
```

## Developing

Requires Node 20+.

```bash
npm install
npm run dev       # Vite dev server + Electron, with hot reload for the renderer
```

```bash
npm run typecheck # tsc --noEmit for both the main and renderer projects
npm run build      # compiles main + bundles renderer into dist/ and dist-electron/
npm run dist        # build + package an installer with electron-builder
```

## Configuring sources

Everything is configured from the **Settings** tab — nothing is scanned or fetched until you add
it there:

- **Local folders** — pick any folder on disk; **Library → Scan folders** walks it recursively.
- **SMB shares** — host, share name, optional sub-path, and credentials for a share (e.g.
  `\\192.168.1.20\media`); **Network → Scan shares** lists its media files. Clicking one opens
  the built-in player and streams it live: the main process opens a raw SMB2 file handle and
  serves byte ranges to an in-app `<video>`/`<audio>` element over a loopback-only HTTP server
  (`127.0.0.1`, random port, random per-session token), so seeking issues real ranged reads
  against the share instead of waiting on a full download.
- **Search sites** — a URL template (`{query}` placeholder) plus CSS selectors for the result
  list, title, download link, size, and seeders. If a site's search results link to a details
  page rather than the magnet/`.torrent` link directly, set the "detail-page link selector" too
  and the app does the second fetch for you. If the site renders its results with JavaScript
  (check "View Page Source" vs. "Inspect" in your browser — if a selector only matches in
  Inspect, that's why), tick "Site renders results with JavaScript": fetches for that profile go
  through a hidden `BrowserWindow` (Electron's own Chromium — see `search/headlessFetch.ts`)
  instead of a plain HTTP request, so the page actually executes before scraping. Slower, so it's
  opt-in per profile rather than the default.

## Artwork

**Fetch artwork** (Library page) looks up a cover/poster for every video/audio item missing one,
across the whole library (local and network), via the iTunes Search API (Apple's public, keyless
catalog search) — no scraping, no API key to configure, no user interaction beyond clicking the
button. The search term is guessed from the filename (strip scene-release noise like
`1080p`/`x264`/group tags, pull out a year if present) and is best-effort: some filenames won't
match anything usable, and existing artwork is never overwritten. Found artwork is stored as a
remote URL on the item (not downloaded/cached locally), so it needs the network to load once
fetched, same as any `<img src>`.

## Known limitations (first version)

- The bundled `smb2` package's public API is read-only and whole-file (`readdir`/`readFile`), with
  no `stat` call — so directory listing still can't report file sizes/dates during a scan (shown
  as `—`), and directory detection works by attempting to list each entry. Streaming works around
  the whole-file limitation by reaching into the package's internal SMB2 READ request plumbing
  directly (`smbRawClient.ts`) to issue real offset/length reads instead — that's also where the
  actual file size used for playback (`Content-Length`/`Content-Range`) comes from, via the
  CREATE response's `EndofFile` field, decoded correctly for files >4GB (the package's own
  `readFile` helper truncates that field at 32 bits).
- The streaming relay server binds to `127.0.0.1` only and gates access behind an unguessable
  per-session token, but there's no additional auth layer — anything else running as the same OS
  user on the machine could reach it while a stream is open.
- Site profiles assume server-rendered HTML search results; JS-rendered result pages aren't
  supported without a headless browser, which this build doesn't include.
- Credentials for network shares are stored locally via `electron-store`, unencrypted on disk.
- Not tested against a live SMB server in this environment (no Samba available in the sandbox
  this was built in) — verified via unit-level smoke tests of the request pipeline and HTTP relay
  (module loads, correct 404/500 handling, no hangs/crashes) plus a full Electron launch under
  Xvfb with no runtime errors. Please confirm against your actual NAS/share.
- The iTunes Search API (`itunes.apple.com`) wasn't reachable from this sandbox's network policy
  either, so artwork fetching is verified only up through "the request fails gracefully and
  returns no artwork" (confirmed — the 403 from this sandbox's policy is handled identically to
  a real "no results" response). The actual lookup returning real artwork is unverified; please
  confirm on a real network.
