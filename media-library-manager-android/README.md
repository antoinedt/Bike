# Media Library Manager — Android

The Android counterpart to the `media-library-manager/` desktop app: the same three sources
(local device, local network SMB, configurable site search with torrent-app handoff), adapted to
Android's platform idioms rather than sharing a codebase with the Electron app — the storage,
network, and media-playback APIs are different enough that trying to share code would fight the
platform on all three.

Native Kotlin + Jetpack Compose, matching the conventions of this repo's existing Bike app (same
version catalog style, `di/ServiceLocator` manual DI, dark Material3 theme).

## How it maps to the desktop app

| Desktop | Android | Why different |
|---|---|---|
| Recursive fs walk over user-picked folders | `MediaStoreScanner` queries `MediaStore` (video/audio/images) | Scoped storage (Android 10+) means a raw filesystem walk needs `MANAGE_EXTERNAL_STORAGE`, a permission Play Store restricts heavily. MediaStore is the platform-sanctioned way to enumerate device media and needs only the granular `READ_MEDIA_*` permissions. There's no "add a folder" step — the whole device's indexed media is scanned. |
| `smb2` (Node) via readdir + a raw-protocol hack for streaming | `SmbShareBrowser` + `SmbDataSource`, both on **jcifs-ng** | jcifs-ng is a mature, pure-Kotlin/Java-compatible SMB2/3 client with real `length()`/`lastModified()` and true random-access reads (`SmbRandomAccessFile`) — no need to reach into protocol internals the way the Node package required. |
| Loopback HTTP relay server + `<video>`/`<audio>` | `SmbDataSource` (a Media3 `DataSource`) + ExoPlayer `PlayerView`, no server | ExoPlayer can pull from a custom in-process `DataSource` directly — ranged/seekable reads against the SMB share with no local server needed at all. |
| Cheerio | **Jsoup** | Same idea, JVM equivalent: CSS selectors over fetched HTML, `abs:href` resolves relative links exactly like the desktop's URL resolution. |
| `shell.openExternal` / download + `shell.openPath` | `Intent(ACTION_VIEW)`, `.torrent` downloaded to cache + shared via `FileProvider` | Android's equivalent of "open with the OS default app" is an implicit intent; there's no direct analogue to opening a remote URL in another app for an arbitrary file type, so `.torrent` files are downloaded first (same as desktop) and handed off via a `content://` URI. |
| electron-store (JSON on disk) | Jetpack **DataStore** (Preferences, JSON-serialized `AppSettings`) | Platform-idiomatic equivalent; same shape (network shares + site profiles), serialized with kotlinx.serialization. |

Local playback opens with the OS's default app immediately (same as desktop — it's already
on-disk, no reason to route it through the in-app player). Network playback streams live inside
the app via ExoPlayer.

## Project layout

```
app/src/main/java/com/medialibrary/manager/
├── MainActivity.kt         Compose host: bottom nav (Library/Network/Search/Settings), permissions, player overlay
├── MediaLibraryApplication.kt   Initializes ServiceLocator
├── di/ServiceLocator.kt     Manual DI, mirrors the Bike app's pattern
├── model/Models.kt          MediaItem / NetworkShare / SiteProfile / AppSettings
├── data/SettingsRepository.kt   DataStore-backed config
├── library/MediaStoreScanner.kt Local device scan via ContentResolver
├── network/
│   ├── SmbContextFactory.kt  jcifs-ng CIFSContext/URL building from NetworkShare
│   ├── SmbShareBrowser.kt    Recursive share listing (real size/mtime from jcifs-ng)
│   └── SmbDataSource.kt      Media3 DataSource for live streaming playback
├── search/SiteSearchRepository.kt  Jsoup-based generic scraper
├── torrent/TorrentHandoff.kt Intent-based magnet:/.torrent handoff
└── ui/                       MediaLibraryViewModel + Compose screens/components
```

## Permissions

- `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` / `READ_MEDIA_IMAGES` (API 33+), `READ_EXTERNAL_STORAGE`
  (API ≤32) — requested at launch, needed for the Library scan.
- `INTERNET` — SMB (TCP, not HTTP, but still needs this permission), site search, `.torrent`
  downloads.
- No `MANAGE_EXTERNAL_STORAGE`, no `QUERY_ALL_PACKAGES` — torrent handoff uses an implicit
  `ACTION_VIEW` intent and lets the OS resolve/chooser whatever's installed.

## Legal / scope note

Same as the desktop app: no torrent client, tracker, or indexer is embedded, and no sites ship
pre-configured. Search only runs against sites you add yourself in **Settings**, and downloads are
handed off to whatever app the OS has registered for `magnet:` links or `.torrent` files — this
app never downloads or manages torrent content itself. Only use it against sites and content
you're legally permitted to search and download.

## Building

Requires the Android SDK (compileSdk 35, JDK 17+) and network access to Google's Maven repo
(`dl.google.com`, for AGP + AndroidX/Media3) and Maven Central (for jcifs-ng/Jsoup/OkHttp/etc).

```bash
./gradlew assembleDebug
```

**This was not build-verified in the sandbox that scaffolded it** — same limitation the existing
Bike app's README already documents (no Android SDK there), *and* this session's outbound network
policy specifically blocks `dl.google.com`, so even `./gradlew help` fails at the AGP-plugin-fetch
step before ever reaching a missing-SDK error. I've been as careful as I can be about the Media3
`DataSource`/jcifs-ng/Jsoup/Compose API shapes (all follow well-established, stable patterns), but
none of it has compiled or run. Please build and smoke-test on a real machine before relying on
it, and expect to fix the inevitable small things a real compiler would have caught — most likely
candidates: exact Media3 1.4.1 API surface, and how Android's `Uri` (re-)encodes special
characters in `smb://` URLs built from filenames (spaces, non-ASCII) before jcifs-ng parses them.

## Known limitations (first version)

- No folder-picking UI — Library always scans the device's full MediaStore-indexed media. If you
  want a specific folder excluded, that's an OS-level "exclude from media scan" setting, not
  something this app currently offers.
- Network share credentials are stored via DataStore, unencrypted on disk (same caveat as the
  desktop app's electron-store).
- Site profiles assume server-rendered HTML; JS-rendered result pages need a headless browser,
  which this build doesn't include (matches the desktop app's same limitation).
- Not tested against a live SMB server or real device — see the build note above.
