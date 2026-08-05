# Media Library Manager

A cross-platform (Windows/macOS/Linux) desktop app for managing a media library from three
sources:

- **Local device** — folders on this machine, recursively scanned for video/audio/image files.
- **Local network** — SMB/Windows shares (e.g. a NAS), browsed directly over the network.
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
│   ├── network/smbClient.ts SMB2 share listing / file fetch
│   ├── search/siteSearch.ts cheerio-based generic scraper driven by SiteProfile selectors
│   └── torrent/handoff.ts   magnet: → shell.openExternal; .torrent → download + shell.openPath
├── renderer/              React UI (Vite)
│   ├── App.tsx, components/, pages/
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
  `\\192.168.1.20\media`); **Network → Scan shares** lists its media files. Opening a network
  file downloads it to a temp folder first, then hands it to your default player — there's no
  direct streaming from the share in this version.
- **Search sites** — a URL template (`{query}` placeholder) plus CSS selectors for the result
  list, title, download link, size, and seeders. If a site's search results link to a details
  page rather than the magnet/`.torrent` link directly, set the "detail-page link selector" too
  and the app does the second fetch for you.

## Known limitations (first version)

- The bundled SMB2 client is minimal: it has no `stat` call, so network file sizes/dates aren't
  available (shown as `—`), and directory detection works by attempting to list each entry.
- Network playback is download-then-open, not streaming.
- Site profiles assume server-rendered HTML search results; JS-rendered result pages aren't
  supported without a headless browser, which this build doesn't include.
- Credentials for network shares are stored locally via `electron-store`, unencrypted on disk.
