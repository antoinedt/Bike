# Media Library Manager — Android

The Android counterpart to the `media-library-manager/` desktop app: the same three sources
(local device, local network SMB, configurable site search with in-app torrent downloads), adapted to
Android's platform idioms rather than sharing a codebase with the Electron app — the storage,
network, and media-playback APIs are different enough that trying to share code would fight the
platform on all three.

Native Kotlin + Jetpack Compose, matching the conventions of this repo's existing Bike app (same
version catalog style, `di/ServiceLocator` manual DI, dark Material3 theme).

## How it maps to the desktop app

| Desktop | Android | Why different |
|---|---|---|
| Recursive fs walk over user-picked folders | `MediaStoreScanner` (whole device) + `LocalFolderScanner` (SAF-picked folders) | Scoped storage (Android 10+) means a raw filesystem walk needs `MANAGE_EXTERNAL_STORAGE`, a permission Play Store restricts heavily. With no folders added, Library scans the whole device via `MediaStore` (needs only the granular `READ_MEDIA_*` permissions); adding a folder (Storage Access Framework tree picker) scopes the scan to just that tree via `DocumentFile`, same idea as the desktop's folder list. |
| `smb2` (Node) via readdir + a raw-protocol hack for streaming | `SmbShareBrowser` + `SmbDataSource`, both on **jcifs-ng** | jcifs-ng is a mature, pure-Kotlin/Java-compatible SMB2/3 client with real `length()`/`lastModified()` and true random-access reads (`SmbRandomAccessFile`) — no need to reach into protocol internals the way the Node package required. |
| Loopback HTTP relay server + `<video>`/`<audio>` | `SmbDataSource` (a Media3 `DataSource`) + ExoPlayer `PlayerView`, no server | ExoPlayer can pull from a custom in-process `DataSource` directly — ranged/seekable reads against the SMB share with no local server needed at all. |
| Cheerio | **Jsoup** | Same idea, JVM equivalent: CSS selectors over fetched HTML, `abs:href` resolves relative links exactly like the desktop's URL resolution. |
| Hidden `BrowserWindow` for JS-rendered sites | Off-screen `WebView` (`HeadlessWebViewFetcher`) | Same idea via the platform's own engine instead of Electron's Chromium — opt-in per site profile, waits for the configured selector to appear before extracting the rendered HTML. |
| `shell.openExternal` / download + `shell.openPath` | `Intent(ACTION_VIEW)`, `.torrent` downloaded to cache + shared via `FileProvider` | Android's equivalent of "open with the OS default app" is an implicit intent; there's no direct analogue to opening a remote URL in another app for an arbitrary file type, so `.torrent` files are downloaded first (same as desktop) and handed off via a `content://` URI. |
| electron-store (JSON on disk) | Jetpack **DataStore** (Preferences, JSON-serialized `AppSettings`) | Platform-idiomatic equivalent; same shape (folders, network shares, site profiles), serialized with kotlinx.serialization. |
| iTunes Search API artwork fetcher | Same API, Kotlin port (`ArtworkFetcher`) | Identical approach on both platforms — no API key, no scraping. |

Video and audio — local and network — play in the app's own full-screen ExoPlayer (a TV often has
no other app that opens a video file). Network items stream live from the share through
`SmbDataSource`, which keeps 8 reads in flight in parallel and ~8 MB buffered ahead: jcifs-ng 2.1.x
caps each SMB read at ~64 KB and waits for each reply, so one-read-at-a-time streaming is limited
by network latency rather than bandwidth (measured: 8 Mbit/s → 78 Mbit/s at a 30 ms round trip).
While buffering, on an error, or whenever the controls are showing, the player shows a diagnostics
panel: buffer level, real network speed, codecs and chosen decoders, tracks the device can't play,
dropped frames, and the full error text.

## Project layout

```
app/src/main/java/com/medialibrary/manager/
├── MainActivity.kt         Compose host: bottom nav (Library/Network/Search/Settings), permissions, player overlay
├── MediaLibraryApplication.kt   Initializes ServiceLocator
├── di/ServiceLocator.kt     Manual DI, mirrors the Bike app's pattern
├── model/Models.kt          MediaItem / NetworkShare / SiteProfile / AppSettings
├── data/SettingsRepository.kt   DataStore-backed config
├── library/
│   ├── MediaStoreScanner.kt   Whole-device scan via ContentResolver
│   └── LocalFolderScanner.kt  SAF-picked folder tree scan via DocumentFile
├── network/
│   ├── SmbContextFactory.kt  jcifs-ng CIFSContext/URL building from NetworkShare
│   ├── SmbShareBrowser.kt    Recursive share listing (real size/mtime from jcifs-ng)
│   └── SmbDataSource.kt      Media3 DataSource for live streaming playback
├── search/
│   ├── SiteSearchRepository.kt    Jsoup-based generic scraper
│   └── HeadlessWebViewFetcher.kt  Off-screen WebView fetch for JS-rendered sites (opt-in per profile)
├── artwork/ArtworkFetcher.kt Title-guessing + iTunes Search API cover/poster lookup
├── torrent/
│   ├── TorrentEngine.kt      In-app downloads on libtorrent (libtorrent4j); plain Kotlin, tested on a JVM
│   └── TorrentHandoff.kt     "Other app": intent-based magnet:/.torrent handoff
├── transfer/
│   ├── TransferStorage.kt    Read/write device Downloads, SAF folders and SMB shares (never overwrites)
│   ├── TransferManager.kt    App-wide: torrents, saving finished ones to their folder, "Copy to…" queue
│   └── TransferService.kt    Foreground service + progress notification while anything is running
└── ui/                       MediaLibraryViewModel + Compose screens/components
```

## Permissions

- `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` / `READ_MEDIA_IMAGES` (API 33+), `READ_EXTERNAL_STORAGE`
  (API ≤32) — requested at launch, needed for the Library scan.
- `INTERNET` — SMB (TCP, not HTTP, but still needs this permission), site search, `.torrent`
  downloads.
- `FOREGROUND_SERVICE` (+ `_SPECIAL_USE` on Android 14+, `_DATA_SYNC` on 10–13) and
  `POST_NOTIFICATIONS` (asked at launch on Android 13+) — downloads and copies keep running, with a
  progress notification, when you leave the app.
- Local folders are picked with read **and write** access, so "Copy to…" and finished downloads
  can be saved into them. Folders added before this version only have read access: use
  **Change folder…** in Settings and pick the same folder again to allow writing.
- No `MANAGE_EXTERNAL_STORAGE`, no `QUERY_ALL_PACKAGES` — "Other app" uses an implicit
  `ACTION_VIEW` intent and lets the OS resolve/chooser whatever's installed.

## Downloads and "Copy to…"

- **Downloads tab** (phone and TV): paste a magnet link / info hash / `.torrent` link, open a
  `.torrent` file, or use **Download** on a search result. Live progress, speed, peers and time
  left; pause, resume, remove. Downloads stop sharing as soon as they're complete and pick up where
  they left off after the app (or phone) restarts, using libtorrent's resume data.
- libtorrent writes into app storage first (it needs real file paths, which SAF folders and SMB
  shares don't have); once a download is complete it's copied to the folder chosen under
  **Downloads → Saving to** (or **Settings → Torrent downloads**) — this device's
  `Downloads/MediaLibraryManager`, any of your local folders, or a network share — and removed
  from app storage. If that's a Library folder or share, the Library rescans it.
- **Copy to…** (the copy icon on every Library tile): copies a local or network item into any
  folder of any location the app knows — e.g. from the NAS to the phone, from the phone to the NAS,
  or between two shares — with **New folder…**. Copies run one at a time in the background,
  never overwrite (an existing name gets " (2)"), and show progress on the tile and in Downloads.
  This replaces the old network-only Download button (copy to "This device · Downloads" for that).
- **Send to another device**: start a download on another computer, phone or TV running this app
  ("Send to…" on search results and in the Downloads tab). Devices find each other through a hidden
  `.media-library-manager` folder on a network share; pick the same share under **Settings → Send to
  another device** on each one. The receiving device starts it while the app is open there and the
  sender gets a "started on …" message back. Same format on Android and desktop (checked by
  `media-library-manager-android/engine-test/run.sh`).
- **Cast** (phone): the Cast button next to the Library filter finds Google TV / Chromecast
  (Google Cast) and DLNA TVs such as Samsung on the Wi-Fi; once one is chosen, tapping a video, song
  or photo plays it there, with a remote-control bar (play/pause, seek, stop). The TV streams the
  file from the phone (a foreground notification keeps it going with the screen off). No format
  conversion: the TV must support the file; MP4/H.264 is safest.
- Tests: `engine-test/run.sh` runs the torrent engine against a local WebTorrent seeder on a
  desktop JVM, plus the casting clients (a castv2-based fake Chromecast; gmediarender for DLNA) (magnet, .torrent file and link, pause/resume, restart, remove, bad input).

## Legal / scope note

Same as the desktop app: the app includes a BitTorrent client (libtorrent) but no tracker or
indexer, and no sites ship pre-configured. Search only runs against sites you add yourself in
**Settings**; the Downloads tab only downloads links you give it. While a download runs, BitTorrent
shares the pieces you already have with other peers (that's how the protocol works); once it's
complete the app stops sharing it. Only use it for content you're legally permitted to download.

## Building

Requires the Android SDK (compileSdk 35, JDK 17+) and network access to Google's Maven repo
(`dl.google.com`, for AGP + AndroidX/Media3) and Maven Central (for jcifs-ng/Jsoup/OkHttp/etc).

```bash
./gradlew assembleDebug
```

**This was not build-verified in the sandbox that scaffolded it** — same limitation the existing
Bike app's README already documents (no Android SDK there), *and* this session's outbound network
policy specifically blocks `dl.google.com`, so even `./gradlew help` fails at the AGP-plugin-fetch
step before ever reaching a missing-SDK error. This repo's CI (`.github/workflows/build-media-library-android.yml`,
mirroring the Bike app's own workflow) runs on GitHub's unrestricted runners and is the actual
build verification — check its latest run before trusting a given commit compiles. I've been as
careful as I can be about the Media3 `DataSource`/jcifs-ng/Jsoup/Coil/Compose API shapes (all
follow well-established, stable patterns), but local edits between CI runs are unverified.
Likely candidates for a real compiler to catch first: exact Media3 1.4.1 API surface, and how
Android's `Uri` (re-)encodes special characters in `smb://` URLs built from filenames (spaces,
non-ASCII) before jcifs-ng parses them.

## Artwork

**Fetch artwork** (Library screen) looks up a cover/poster for every video/audio item missing one,
via the iTunes Search API (Apple's public, keyless catalog search) — no scraping, no API key, no
user interaction beyond tapping the button. The search term is guessed from the filename (strip
scene-release noise like `1080p`/`x264`/group tags, pull out a year if present) and is
best-effort: some filenames won't match anything, and nothing gets overwritten once an item has
artwork. Artwork association is in-memory only (not persisted to DataStore), so it needs
re-fetching after each fresh scan or app restart — consistent with `localItems`/`networkItems`
themselves not being persisted either.

## Android TV / Google TV

Same APK runs on phones/tablets and TV — no separate build. `AndroidManifest.xml` declares
`android.hardware.touchscreen` and `android.software.leanback` as `required="false"` (so a non-touch
device isn't filtered out) and adds `CATEGORY_LEANBACK_LAUNCHER` alongside the normal `LAUNCHER`
category on `MainActivity`, so it shows up in the TV home screen's app row (using
`@drawable/tv_banner`, a 320x180 banner, as its tile there). All interactive elements are standard
Compose `clickable`/`Button`/`Checkbox` composables, which are focusable and D-pad-navigable by
default — there's no touch-only gesture (swipe, long-press, drag) anywhere in the app that a remote
couldn't reach. `PlayerOverlay`'s `PlayerView` is explicitly set focusable and requests focus on
open so D-pad/media-key input reaches ExoPlayer's own controls.

On a TV (detected via `UiModeManager` / the leanback feature) the layout adapts so the media grid
gets most of the screen: the tabs move to a side rail, the Search tab and the Settings "Search
sites" section are hidden, and the Library's filter/path/tags sit on one line above the grid with
the scan and fetch buttons on one line below it. Moving the D-pad between those bars and the grid
lands on the first control or tile rather than whichever is nearest.

Not verified on a real TV or emulator (no Android SDK/emulator in the sandbox that added this) —
install a CI-built APK on an actual Google TV / Android TV device and check: it appears on the home
screen row, and D-pad navigation/text entry (search box, Settings fields, the TMDb API key) works
acceptably. A Bluetooth mouse+keyboard paired to the TV makes every screen fully usable even before
any further D-pad-specific polish.

## Known limitations (first version)

- Local folders are opt-in scoping: with none added, Library still scans the whole device via
  MediaStore (same zero-config behavior as before); adding a folder switches that scan to only
  the folders you've picked (SAF tree URIs), same idea as the desktop app's folder list.
- Network share credentials are stored via DataStore, unencrypted on disk (same caveat as the
  desktop app's electron-store).
- Site profiles assume server-rendered HTML; JS-rendered result pages need a headless browser,
  which this build doesn't include (matches the desktop app's same limitation).
- Not tested against a live SMB server or real device — see the build note above. The torrent
  engine and the SMB write steps "Copy to…" uses were tested on a desktop JVM (local seeder, local
  test SMB server); the Android UI, service and SAF/MediaStore writes still need a real device.
- The APK carries libtorrent's native code for arm64, 32-bit ARM and x86_64, which makes it
  noticeably bigger than before.
- iTunes Search API access wasn't reachable from this sandbox either (same network-policy class
  as `dl.google.com`), so the artwork feature is untested beyond title-guessing logic; verify the
  actual lookup on a real device.
