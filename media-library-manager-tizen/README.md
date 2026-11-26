# OmniPlay — Samsung TV (Tizen) (formerly Media Library Manager)

The third version of Media Library Manager, alongside the desktop app (`media-library-manager/`)
and the Android / Google TV app (`media-library-manager-android/`). This one is deliberately small:
**browse and play the video and audio on a USB drive plugged into the TV** (or the TV's own
storage), plus the Fizz TV guide (TV tab).

What it doesn't have, compared to the other two:

- **No network shares (SMB/NAS).** Samsung TV apps are sandboxed web apps with no raw network
  socket access, so there's no way to speak the SMB protocol from one. To play from your NAS on a
  Samsung TV, use the Android app on a Google TV device plugged into it instead.
- **No site search / torrent hand-off.** There's nothing on a TV to hand a torrent off to.
- **No artwork or info lookup.** Titles are cleaned up from the filename (same rules as the other
  apps: `The.Movie.2019.1080p.x264-GRP.mkv` → "The Movie", 2019), and the TV's own thumbnails are
  shown when it has them.

## Using it

- **Video / Audio** tabs at the top; arrow keys move between them and the tiles.
- **OK** plays the highlighted tile.
- In the player: **OK** or **Play/Pause** toggles, **◀ / ▶** (or **Rewind / Fast-forward**) jump
  10 seconds, **Stop** or **Return** goes back to the list.
- **Return** on the list closes the app.
- Plug the USB drive in *before* opening the app (or reopen it after plugging in).

Which files actually play is up to the TV's built-in player. Modern Samsung TVs handle MP4/MKV
with H.264/H.265 and common audio fine; old formats (some AVI/WMV) may not. If a file can't play,
the player says so.

## TV tab (Fizz TV guide)

The **TV** tab shows the Fizz TV guide for the channels in your plan (the Fizz app's
"Guide → My plan"), for yesterday through the next 6 days. Guide only: no live playback.

- The first time, choose **Sign in with a code**: the TV shows a code; on your phone or computer
  go to **auth.fizz.ca/activate**, enter it and sign in. The TV remembers the login (Return /
  restarts don't sign you out; only **Sign out** or Fizz rejecting the saved login does).
- **Down** goes into the guide at what's on now. **Up/Down** moves between channels,
  **Left/Right** between shows (the guide scrolls with you), **OK** shows a show's details,
  **Return** closes them / goes back to the tabs.

This is the only part of the app that uses the internet (to auth.fizz.ca, a2.vmedia.ca and
images.cdn.vmedia.ca, listed in `config.xml`).

## Installing on your Samsung TV

Samsung doesn't let you install an app from a downloaded file the way Android does. Your own apps
go on through **Developer Mode**, pushed from a computer on the same network, using Samsung's free
**Tizen Studio**. It's a one-time setup of about 30 minutes. After that, updating is one click.

You need: a Samsung TV from 2017 or newer, a Windows/Mac/Linux computer on the same Wi-Fi/network
as the TV, and a (free) Samsung account.

### 1. Install Tizen Studio on the computer

1. Download and install **Tizen Studio** (the version *with IDE*) from the Tizen developer site.
2. When it finishes, the **Package Manager** opens (or open it from the Tizen Studio menu: Tools →
   Package Manager). Go to the **Extension SDK** tab and install:
   - **Samsung TV Extensions** (the newest version listed)
   - **Samsung Certificate Extension**

### 2. Put the TV in Developer Mode

1. Find your computer's IP address (Windows: `ipconfig`; Mac: System Settings → Network).
2. On the TV, open **Apps**.
3. With the Apps screen showing, type **1 2 3 4 5** on the remote. If your remote has no number
   keys, press the **123** button (or the color/number button) to bring up the on-screen number
   pad.
4. A **Developer Mode** box appears: switch it **On**, enter the computer's IP address under
   **Host PC IP**, and press OK.
5. **Restart the TV** fully: unplug it for 30 seconds (or hold the remote's power button until
   the TV restarts). Developer Mode only takes effect after a real restart.
6. Note the TV's own IP address (TV Settings → General → Network → Network Status → IP Settings).

### 3. Connect Tizen Studio to the TV

1. In Tizen Studio: **Tools → Device Manager**.
2. Click **Remote Device Manager** → **+**. Name: anything. IP: the **TV's** IP address. Port:
   `26101`. Click Add.
3. Switch its **Connection** toggle on. The TV should appear in Device Manager's list.

### 4. Create your signing certificate (once)

The TV only runs developer apps signed with a certificate that includes that specific TV.

1. **Tools → Certificate Manager** → **+**.
2. Choose **Samsung** → **TV**. Profile name: e.g. `mytv`.
3. **Create a new author certificate**: any name and password (remember it). Sign in with your
   Samsung account when asked.
4. **Distributor certificate**: keep privilege **Public**. Your connected TV's ID (DUID) is filled
   in automatically. That's why the TV has to be connected in step 3 first.
5. Finish. Make sure the new profile is the active one (it has a check mark).

### 5. Install the app

1. **File → Import → General → Existing Projects into Workspace** → Next.
2. **Select root directory** → choose the `media-library-manager-tizen/app` folder from this
   repository → Finish.
3. Right-click the **MediaLibraryManager** project → **Run As → Tizen Web Application**.

Tizen Studio builds and signs the app, installs it on the TV, and launches it. From then on it's
in the TV's Apps list like any other app. To update after pulling new code, do step 5.3 again.

If Tizen Studio complains about the project's platform version when importing, right-click the
project → Properties → Tizen Studio → Platform, and pick the TV platform it offers.

**Command-line alternative** (instead of step 5), from a terminal with Tizen Studio's `tools`
and `tools/ide/bin` folders on your PATH:

```bash
cd media-library-manager-tizen/app
tizen build-web -- .
tizen package -t wgt -s mytv -- .buildResult   # "mytv" = your certificate profile name
sdb connect <TV-IP>
tizen install -n "<the .wgt file name package printed>" -- .buildResult
```

### If something goes wrong

- **TV doesn't show up in Device Manager**: the TV wasn't fully restarted after turning on
  Developer Mode, or the Host PC IP entered on the TV isn't your computer's current IP.
- **Install fails with a certificate/signature error**: the certificate profile was created
  without the TV connected, so it doesn't include the TV's ID. Recreate the distributor
  certificate (step 4) with the TV connected.
- **App opens but says it couldn't read your files**: the app tries the TV's media index first and
  then reads the USB drive's folders directly, and the message says what failed. Most likely a
  privilege in `app/config.xml` that this TV model wants named differently.

## How it's built

Plain HTML/CSS/JavaScript, no framework and no build step. That keeps it compatible with the older
web engines in 2017–2020 Samsung TVs: the JS is ES5 only (checked by compiling it for ES5), and
the CSS avoids CSS variables, CSS Grid, flexbox `gap` and `aspect-ratio`.

```
app/
├── config.xml        Tizen app manifest: id, TV profile, privileges (media index, USB, remote keys)
├── index.html        Library view + player view
├── css/style.css     10-foot TV layout, big focus highlight
├── js/titleGuess.js  Filename → title/year cleanup (same rules as desktop/Android, ported by hand)
└── js/app.js         Finding media, rendering, playback, remote-control keys
test/
└── run.js            Browser test against a stand-in for the TV's API
```

- **Finding media:** first `tizen.content.find()` (the TV's own media index, which covers USB
  drives it has scanned). If that's unavailable or empty, it walks every mounted USB drive's
  folders itself with `tizen.filesystem` (up to 8 levels deep, skipping hidden folders like
  `.Trashes`).
- **Remote control:** Samsung's TV browser doesn't reliably move focus with the arrow keys on its
  own, so `app.js` does it: arrows move to the nearest tile/button in that direction on screen.
  The media keys (Play/Pause, Stop, FF, Rewind) are registered with `tizen.tvinputdevice`, since
  the TV doesn't deliver them to an app otherwise.
- **Return key:** closes the player, or exits the app from the list. A Tizen app has to handle
  this itself.

## Testing

There's no Tizen emulator in this repo's tooling, so `test/run.js` runs the app in headless
Chromium against a stand-in for the TV's `tizen` API. It checks listing and title cleanup, sorting,
arrow-key focus movement, real video and audio playback, the remote's media keys, Return/Stop,
empty and error messages, and the USB-folder fallback.

```bash
cd media-library-manager-tizen/test
npm install
node run.js        # needs ffmpeg installed, to generate its sample media
```

**Not yet verified on a real Samsung TV.** What only a real TV can confirm: the exact behavior of
its media index and folder access (privileges in `config.xml` are the likeliest thing to need a
tweak), which files its player accepts, and how the remote feels.
