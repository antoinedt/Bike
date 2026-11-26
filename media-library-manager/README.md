# OmniPlay (formerly Media Library Manager)

A cross-platform (Windows/macOS/Linux) desktop app for managing a media library from three
sources:

- **Local device** — folders on this machine, recursively scanned for video/audio/image files.
- **Local network** — SMB/Windows shares (e.g. a NAS), browsed and **streamed live** (seekable,
  no full download first) via an in-app player.
- **Search** — a user-configured search against a site you have the legal right to use.
- **Downloads** — a built-in torrent client: **Download** on a search result (or a pasted magnet
  link / `.torrent` URL / `.torrent` file) downloads in-app with live progress, pause/resume and
  remove. Downloads stop as soon as they finish (no seeding afterwards) and pick up where they
  left off after the app restarts. If the download folder (Settings → Torrent downloads) is inside
  one of your Library folders, finished downloads appear in the Library automatically. **Open in
  torrent app** still hands a result to your OS's default torrent app instead.
- **Send to another device**: start a download on another computer, phone or TV running this app
  ("Send to…" on search results and on the Downloads page). Devices find each other through a hidden
  `.media-library-manager` folder on a network share; pick the same share under **Settings → Send to
  another device** on each one. The receiving device starts it while the app is open there and the
  sender gets a "started on …" message back. Same format on Android and desktop (checked by
  `media-library-manager-android/engine-test/run.sh`).
- **Cast…** (in an item's details): plays a video, song or photo on a Google TV / Chromecast
  (Google Cast) or a DLNA TV such as a Samsung, with a remote-control bar (play/pause, ±30 s, seek,
  stop). The TV streams the file from the app (local, or relayed from the share), so keep the app
  open while casting. The TV must support the file's format (no conversion); MP4/H.264 is safest.
- **Copy to…** (in an item's details): copies a local or network item into a folder inside any of
  your Library folders or network shares (with **New folder**), or any folder on this computer.
  Copies run one at a time in the background, never overwrite (an existing name gets " (2)"),
  show progress on the Downloads page, and the Library rescans wherever the copy landed.

Built with Electron + React + TypeScript.

## TV tab (Fizz TV guide)

The **TV** tab shows the Fizz TV guide for the channels in your plan (the Fizz app's
"Guide → My plan"): a timeline per channel, today plus yesterday and the next 6 days; click a
show for its details. Guide only: no live playback.

You're asked to sign in only when you open the tab, in a small window with Fizz's own sign-in
page. The login is saved like the Fizz app does (encrypted with Windows' key store): a network
error never wipes it, an expired session is renewed automatically, and you only sign in again if
Fizz rejects the saved login (or you press **Sign out**). Unofficial, for your own account.

## Legal / scope note

The app includes a BitTorrent client ([WebTorrent](https://webtorrent.io/)) but no tracker or
indexer of its own, and ships with no sites pre-configured. The **Search** feature only works
after you add a site profile yourself in **Settings → Search sites**, and it only fetches sites you
explicitly configure; the **Downloads** page only downloads links you give it. While a download is
running, BitTorrent shares the pieces you already have with other peers (that's how the protocol
works); once it's complete the app stops sharing it. Only use this for content you're legally
permitted to download.

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
│   └── torrent/
│       ├── torrentManager.ts  in-app downloads on WebTorrent (add/pause/resume/remove, persistence)
│       ├── torrentIpc.ts      wires it to the UI, notifications, and the Library
│       └── handoff.ts         "Open in torrent app": magnet: → shell.openExternal, .torrent → shell.openPath
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
npm test            # torrent download + "Copy to…" tests (no internet needed; the network-share
                    # copy tests also run if MLM_SMB_TEST points at a test SMB server, see test/)
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

- Network shares use the `smb2` npm package (SMB 2.0.2), patched at startup (`network/smb2Fixes.ts`)
  to sign in with **NTLMv2** (it only did NTLMv1, which current Samba, Synology DSM 7 and Windows 11
  refuse) and to handle a busy server's `STATUS_PENDING` replies. Reads keep 16 requests in flight,
  so streaming over a VPN like ZeroTier isn't capped at 64 KB per round trip (measured against
  Samba 4.19 at 30 ms: 16 → 144 Mbit/s). No SMB signing/encryption: shares that require them won't
  open.
- A share's host can include a port (`nas.local:1445`) for servers not on 445.

- `webtorrent` is pinned to 2.8.5: 3.x needs Node 22, and Electron 33 ships Node 20. Its
  dependency tree in `package-lock.json` was resolved as of late 2025 on purpose — newer
  `parse-torrent` releases changed the `infoHash` type and break 2.8.5, so don't `npm update` those
  packages without re-running `npm test`.
- In-app downloads find peers via trackers, DHT and peer exchange; behind a strict NAT/firewall
  with no UPnP, fewer peers can connect, so downloads may be slower than in a dedicated client.

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
