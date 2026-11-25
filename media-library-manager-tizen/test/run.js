/**
 * Browser test for the Tizen app, run against a stand-in for the TV's `tizen` API (there's no
 * Tizen emulator in CI or most dev machines). Covers: listing + title cleanup, sorting, D-pad
 * focus movement, real video/audio playback, remote media keys, Return/Stop, empty/error
 * states, and the USB-folder fallback. Doesn't cover anything only a real TV can tell you
 * (its actual content index, codecs, remote hardware).
 *
 *   cd media-library-manager-tizen/test && npm install playwright && node run.js
 *
 * Needs ffmpeg on PATH to generate its sample media. Uses WebM/MP3 samples because the
 * open-source Chromium Playwright ships can't decode H.264.
 */
const { chromium } = require("playwright");
const { execFileSync } = require("child_process");
const fs = require("fs");
const path = require("path");
const { pathToFileURL } = require("url");

const APP = pathToFileURL(path.join(__dirname, "..", "app", "index.html")).href;
const MEDIA_DIR = path.join(__dirname, ".media");
const MEDIA = pathToFileURL(MEDIA_DIR).href;
const OUT = path.join(__dirname, "screenshots");

function ensureSampleMedia() {
  fs.mkdirSync(MEDIA_DIR, { recursive: true });
  fs.mkdirSync(OUT, { recursive: true });
  const ff = (args) => execFileSync("ffmpeg", ["-loglevel", "error", "-y"].concat(args), { cwd: MEDIA_DIR });
  if (!fs.existsSync(path.join(MEDIA_DIR, "sample.webm"))) {
    ff(["-f", "lavfi", "-i", "testsrc=duration=30:size=640x360:rate=25", "-f", "lavfi", "-i", "sine=frequency=440:duration=30",
      "-c:v", "libvpx-vp9", "-b:v", "300k", "-c:a", "libopus", "-shortest", "sample.webm"]);
  }
  if (!fs.existsSync(path.join(MEDIA_DIR, "sample.mp3"))) {
    ff(["-f", "lavfi", "-i", "sine=frequency=330:duration=20", "-c:a", "libmp3lame", "sample.mp3"]);
  }
  if (!fs.existsSync(path.join(MEDIA_DIR, "thumb.jpg"))) {
    ff(["-ss", "5", "-i", "sample.webm", "-frames:v", "1", "thumb.jpg"]);
  }
}

function mockTizen(videos, audios, opts) {
  opts = opts || {};
  return `
    window.__exited = false;
    window.__registered = [];
    window.tizen = {
      AttributeFilter: function (a, m, v) { this.attributeName = a; this.matchFlag = m; this.matchValue = v; },
      content: ${opts.noContent ? "undefined" : `{
        find: function (ok, err, dir, filter) {
          ${opts.contentError ? `setTimeout(function () { err({ name: "SecurityError", message: "Permission denied" }); }, 10); return;` : ""}
          var data = filter.matchValue === "VIDEO" ? ${JSON.stringify(videos)} : ${JSON.stringify(audios)};
          setTimeout(function () { ok(data); }, 50);
        }
      }`},
      filesystem: (function () {
        var tree = ${JSON.stringify(opts.usbTree || null)};
        function wrap(node, path) {
          var full = path + "/" + node.name;
          return {
            name: node.name, fullPath: full, isDirectory: !!node.children, isFile: !node.children,
            toURI: function () { return node.uri; },
            listFiles: function (ok) {
              var kids = (node.children || []).map(function (c) { return wrap(c, full); });
              setTimeout(function () { ok(kids); }, 5);
            }
          };
        }
        return {
          listStorages: function (ok) {
            setTimeout(function () {
              ok(tree ? [{ label: "removable_sda1", type: "EXTERNAL", state: "MOUNTED" }, { label: "internal0", type: "INTERNAL", state: "MOUNTED" }]
                      : [{ label: "internal0", type: "INTERNAL", state: "MOUNTED" }]);
            }, 5);
          },
          resolve: function (label, ok, err) {
            setTimeout(function () {
              if (label === "removable_sda1" && tree) ok(wrap({ name: "", children: tree }, "/opt/media/USBDriveA1"));
              else err({ name: "NotFoundError" });
            }, 5);
          }
        };
      })(),
      application: { getCurrentApplication: function () { return { exit: function () { window.__exited = true; } }; } },
      tvinputdevice: { registerKey: function (k) { window.__registered.push(k); } }
    };`;
}

const usbTree = [
  { name: ".Trashes", children: [{ name: "deleted.mp4", uri: MEDIA + "/sample.webm" }] },
  { name: "Movies", children: [
    { name: "Great.Film.2010.1080p.BluRay.mkv", uri: MEDIA + "/sample.webm" },
    { name: "Nested", children: [{ name: "Deep_Clip.MP4", uri: MEDIA + "/sample.webm" }] }
  ] },
  { name: "Music", children: [{ name: "Some_Track.mp3", uri: MEDIA + "/sample.mp3" }, { name: "cover.jpg", uri: "" }] },
  { name: "notes.txt", uri: "" }
];

const videos = [
  { id: "v1", name: "The.Movie.Name.2019.1080p.BluRay.x264-GROUP.mkv", title: "The.Movie.Name.2019.1080p.BluRay.x264-GROUP", contentURI: MEDIA + "/sample.webm", thumbnailURIs: [MEDIA + "/thumb.jpg"], duration: 6720000 },
  { id: "v2", name: "Another_Film_(2004)_720p.mp4", title: "", contentURI: MEDIA + "/sample.webm", thumbnailURIs: [], duration: 5400000 },
  { id: "v3", name: "holiday.mov", title: "Summer Holiday With Tags", contentURI: MEDIA + "/sample.webm", duration: 95000 },
  { id: "v4", name: "Show.S01E02.HDTV.mkv", title: "Show.S01E02.HDTV", contentURI: MEDIA + "/sample.webm", thumbnailURIs: [MEDIA + "/missing.jpg"], duration: 2700000 },
  { id: "v5", name: "clip5.mp4", contentURI: MEDIA + "/sample.webm" },
  { id: "v6", name: "clip6.mp4", contentURI: MEDIA + "/sample.webm" },
  { id: "v7", name: "clip7.mp4", contentURI: MEDIA + "/sample.webm" }
];
const audios = [
  { id: "a1", name: "Song_Title.mp3", title: "Song_Title", contentURI: MEDIA + "/sample.mp3", duration: 20000 },
  { id: "a2", name: "Other Track.mp3", title: "Real Tagged Song", contentURI: MEDIA + "/sample.mp3", duration: 185000 }
];

async function pressRemote(page, keyCode) {
  await page.evaluate((code) => {
    const ev = new KeyboardEvent("keydown", { bubbles: true, cancelable: true });
    Object.defineProperty(ev, "keyCode", { get: () => code });
    (document.activeElement || document).dispatchEvent(ev);
  }, keyCode);
}

const focusedId = (page) => page.evaluate(() => document.activeElement && (document.activeElement.id || document.activeElement.tagName));

(async () => {
  ensureSampleMedia();
  const browser = await chromium.launch({ args: ["--autoplay-policy=no-user-gesture-required", "--allow-file-access-from-files"] });
  const failures = [];
  const check = (cond, msg) => { console.log((cond ? "PASS " : "FAIL ") + msg); if (!cond) failures.push(msg); };

  // 1. Full library
  {
    const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
    const errors = [];
    page.on("pageerror", (e) => errors.push("pageerror: " + e.message));
    page.on("console", (m) => { if (m.type() === "error" && m.location().url.indexOf("missing.jpg") < 0) errors.push("console: " + m.text() + " @ " + m.location().url); });
    await page.addInitScript(mockTizen(videos, audios));
    await page.goto(APP);
    await page.waitForSelector(".card");

    const cards = await page.$$eval(".card", (els) => els.map((e) => {
      const m = e.querySelector(".card-meta");
      return { id: e.id, title: e.querySelector(".card-title").textContent, meta: m ? m.textContent : "" };
    }));
    console.log("cards:", JSON.stringify(cards));
    const byId = {}; cards.forEach((c) => (byId[c.id] = c));
    const titles = cards.map((c) => c.title);
    check(titles.length === 7, "7 video cards rendered");
    check(JSON.stringify(titles) === JSON.stringify(titles.slice().sort((a, b) => a.toLowerCase().localeCompare(b.toLowerCase()))), "cards sorted by title");
    check(byId["item-v1"].title === "The Movie Name" && byId["item-v1"].meta === "2019 · 1:52:00", "scene filename cleaned + year/duration");
    check(byId["item-v2"].title === "Another Film" && byId["item-v2"].meta === "2004 · 1:30:00", "empty tag title falls back to filename guess");
    check(byId["item-v3"].title === "Summer Holiday With Tags" && byId["item-v3"].meta === "1:35", "real tag title kept");
    check(byId["item-v4"].title === "Show S01E02", "HDTV noise stripped");
    check(await page.evaluate(() => window.__registered.length === 6), "6 media keys registered");
    check((await focusedId(page)) === cards[0].id, "first card focused on load");

    // missing thumbnail removed, video fallback glyph shows
    const brokenImgs = await page.$$eval("#item-v4 img", (e) => e.length);
    check(brokenImgs === 0, "broken thumbnail removed (falls back to glyph)");

    await page.screenshot({ path: path.join(OUT, "library.png") });

    await pressRemote(page, 39);
    check((await focusedId(page)) === cards[1].id, "Right moves to next card");
    const cols = await page.evaluate(() => {
      const cards = [...document.querySelectorAll(".card")];
      const top = cards[0].offsetTop;
      return cards.filter((c) => c.offsetTop === top).length;
    });
    console.log("columns:", cols);
    await pressRemote(page, 37);
    await pressRemote(page, 40);
    check((await focusedId(page)) === cards[cols].id, "Down moves to the card below");
    await pressRemote(page, 38);
    await pressRemote(page, 38);
    check((await focusedId(page)) === "tab-video", "Up from first row reaches the tabs");
    await pressRemote(page, 39);
    check((await focusedId(page)) === "tab-audio", "Right on tabs reaches Audio tab");
    await pressRemote(page, 40);
    check((await focusedId(page)) && (await focusedId(page)).startsWith("item-"), "Down from tabs returns to grid");

    // Play a video
    await page.focus("#item-v3");
    await page.keyboard.press("Enter");
    await page.waitForSelector("#player-view:not(.hidden)");
    check(await page.isVisible("#video-player"), "video player shown");
    check(!(await page.isVisible("#audio-player")), "audio player hidden for video");
    check((await page.textContent("#now-playing")) === "Summer Holiday With Tags", "now-playing title");
    await page.waitForFunction(() => document.getElementById("video-player").currentTime > 1, null, { timeout: 10000 });
    check(true, "video actually plays (currentTime advanced)");
    check((await focusedId(page)) === "video-player", "video element focused in player");

    await pressRemote(page, 10252); // play/pause
    check(await page.evaluate(() => document.getElementById("video-player").paused), "Play/Pause key pauses");
    const t0 = await page.evaluate(() => document.getElementById("video-player").currentTime);
    await pressRemote(page, 39); // seek +10
    const t1 = await page.evaluate(() => document.getElementById("video-player").currentTime);
    check(t1 - t0 > 9, "Right on video seeks forward 10s (" + t0.toFixed(1) + " -> " + t1.toFixed(1) + ")");
    await pressRemote(page, 13);
    check(!(await page.evaluate(() => document.getElementById("video-player").paused)), "Enter on video resumes");
    await page.screenshot({ path: path.join(OUT, "player.png") });

    await pressRemote(page, 38);
    check((await focusedId(page)) === "back-button", "Up from video reaches Back button");

    await pressRemote(page, 10009); // Return
    check(await page.isVisible("#library-view"), "Return closes player");
    check(await page.evaluate(() => document.getElementById("video-player").paused), "playback stopped on close");
    check((await focusedId(page)) === "item-v3", "focus restored to the played card");
    check(!(await page.evaluate(() => window.__exited)), "app not exited while closing player");

    // Audio tab
    await page.focus("#tab-audio");
    await page.keyboard.press("Enter");
    const audioTitles = await page.$$eval(".card-title", (els) => els.map((e) => e.textContent));
    console.log("audio titles:", JSON.stringify(audioTitles));
    check(audioTitles.length === 2 && audioTitles[0] === "Real Tagged Song" && audioTitles[1] === "Song Title", "audio tab lists audio");
    await page.focus("#item-a1");
    await page.keyboard.press("Enter");
    await page.waitForSelector("#player-view:not(.hidden)");
    check(await page.isVisible("#audio-player") && !(await page.isVisible("#video-player")), "audio player shown for audio");
    await page.waitForFunction(() => document.getElementById("audio-player").currentTime > 0.5, null, { timeout: 10000 });
    check(true, "audio actually plays");
    await page.screenshot({ path: path.join(OUT, "audio-player.png") });
    await pressRemote(page, 413); // stop
    check(await page.isVisible("#library-view"), "Stop key returns to library");

    await pressRemote(page, 10009);
    check(await page.evaluate(() => window.__exited), "Return on library exits app");

    check(errors.length === 0, "no page/console errors" + (errors.length ? ": " + errors.join(" | ") : ""));
    await page.close();
  }

  // 2. Empty library
  {
    const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
    await page.addInitScript(mockTizen([], []));
    await page.goto(APP);
    await page.waitForFunction(() => document.getElementById("status").textContent.indexOf("No videos") === 0);
    check(true, "empty-state message for video");
    await page.click("#tab-audio");
    check((await page.textContent("#status")).indexOf("No audio") === 0, "empty-state message for audio");
    await page.screenshot({ path: path.join(OUT, "empty.png") });
    await page.close();
  }

  // 3. Not running on a TV
  {
    const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
    const errors = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await page.goto(APP);
    await page.waitForTimeout(300);
    check((await page.textContent("#status")).indexOf("needs to run on a Samsung TV") >= 0, "non-TV message");
    check(errors.length === 0, "no errors without tizen API");
    await page.close();
  }

  // 4. Content API error
  {
    const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
    await page.addInitScript(mockTizen([], [], { contentError: true }));
    await page.goto(APP);
    await page.waitForFunction(() => document.getElementById("status").textContent.indexOf("Couldn't read your video files") === 0);
    console.log("error status:", await page.textContent("#status"));
    check(true, "content API error is surfaced");
    await page.close();
  }

  // 5. Index empty -> falls back to walking the USB drive
  {
    const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
    const errors = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await page.addInitScript(mockTizen([], [], { usbTree }));
    await page.goto(APP);
    await page.waitForSelector(".card");
    const v = await page.$$eval(".card-title", (els) => els.map((e) => e.textContent));
    console.log("usb video titles:", JSON.stringify(v));
    check(v.length === 2 && v[0] === "Deep Clip" && v[1] === "Great Film", "USB fallback finds nested videos, skips hidden folder + non-media");
    await page.click("#tab-audio");
    const a = await page.$$eval(".card-title", (els) => els.map((e) => e.textContent));
    check(a.length === 1 && a[0] === "Some Track", "USB fallback finds audio");
    await page.evaluate(() => document.querySelector(".card").click());
    await page.waitForFunction(() => document.getElementById("audio-player").currentTime > 0.3, null, { timeout: 10000 });
    check(true, "USB-found file plays");
    check(errors.length === 0, "no errors in USB fallback" + (errors.length ? ": " + errors.join(" | ") : ""));
    await page.close();
  }

  // 6. No content API at all -> USB fallback
  {
    const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
    await page.addInitScript(mockTizen([], [], { noContent: true, usbTree }));
    await page.goto(APP);
    await page.waitForSelector(".card");
    check((await page.$$(".card")).length === 2, "works without content API via USB walk");
    await page.close();
  }

  // 7. No content API and no USB drive -> explains why
  {
    const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
    await page.addInitScript(mockTizen([], [], { noContent: true }));
    await page.goto(APP);
    await page.waitForFunction(() => document.getElementById("status").textContent.indexOf("Loading") < 0 && document.getElementById("status").textContent.length > 0);
    console.log("no-content status:", await page.textContent("#status"));
    check((await page.textContent("#status")).indexOf("media index not available") >= 0, "explains missing content API when nothing found");
    await page.close();
  }

  await browser.close();
  console.log(failures.length ? "\n" + failures.length + " FAILURE(S)" : "\nALL PASSED");
  process.exit(failures.length ? 1 : 0);
})();
