/**
 * Media Library Manager — Tizen (Samsung TV) build: local browse + play only.
 *
 * Finds video/audio two ways: first the TV's own media index (tizen.content.find — internal
 * storage plus attached USB drives, the same idea as the Android app's zero-config MediaStore
 * mode), and, if that's unavailable or comes back empty, by walking any mounted USB drive's
 * folders directly (tizen.filesystem). Plays files in a native <video>/<audio> element.
 * No network shares (Tizen web apps have no raw socket access, so SMB isn't reachable), no site
 * search, no artwork lookup.
 *
 * Written as plain ES5 (no bundler, no arrow functions, no optional chaining / ??, callbacks
 * rather than async/await) so it runs on Samsung TVs back to 2017 (Tizen 3.0), not just
 * recent ones.
 */
(function () {
  var VIDEO_EXTENSIONS = ["mp4", "mkv", "avi", "mov", "webm", "m4v", "wmv", "flv", "mpg", "mpeg", "ts", "m2ts"];
  var AUDIO_EXTENSIONS = ["mp3", "flac", "wav", "m4a", "ogg", "aac", "wma", "opus"];
  var MAX_FOLDER_DEPTH = 8;

  var KEY = {
    LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 10009,
    PLAY_PAUSE: 10252, PLAY: 415, PAUSE: 19, STOP: 413, FAST_FORWARD: 417, REWIND: 412
  };
  var DIRECTIONS = {};
  DIRECTIONS[KEY.LEFT] = "left";
  DIRECTIONS[KEY.UP] = "up";
  DIRECTIONS[KEY.RIGHT] = "right";
  DIRECTIONS[KEY.DOWN] = "down";

  var allItems = { video: [], audio: [] };
  var loadErrors = { video: "", audio: "" };
  var currentKind = "video";
  var lastFocusedId = null;

  var libraryView = document.getElementById("library-view");
  var playerView = document.getElementById("player-view");
  var grid = document.getElementById("grid");
  var statusEl = document.getElementById("status");
  var videoPlayer = document.getElementById("video-player");
  var audioPlayer = document.getElementById("audio-player");
  var nowPlaying = document.getElementById("now-playing");
  var backButton = document.getElementById("back-button");

  function setStatus(text) {
    statusEl.textContent = text || "";
    statusEl.style.display = text ? "block" : "none";
  }

  function errorText(err) {
    if (!err) return "";
    return err.message || err.name || String(err);
  }

  function formatDuration(ms) {
    if (!ms || ms <= 0) return "";
    var totalSeconds = Math.round(ms / 1000);
    var h = Math.floor(totalSeconds / 3600);
    var m = Math.floor((totalSeconds % 3600) / 60);
    var s = totalSeconds % 60;
    var mm = h > 0 && m < 10 ? "0" + m : String(m);
    var ss = s < 10 ? "0" + s : String(s);
    return h > 0 ? h + ":" + mm + ":" + ss : mm + ":" + ss;
  }

  function kindOf(filename) {
    var match = /\.([^.]+)$/.exec(filename);
    if (!match) return null;
    var ext = match[1].toLowerCase();
    if (VIDEO_EXTENSIONS.indexOf(ext) >= 0) return "video";
    if (AUDIO_EXTENSIONS.indexOf(ext) >= 0) return "audio";
    return null;
  }

  // The TV indexer's own "title" is often just the filename again (or empty) for files with no
  // embedded tags, so only trust it when it's clearly real metadata; otherwise clean up the
  // filename ourselves.
  function makeItem(kind, id, name, tagTitle, uri, thumbnail, duration) {
    var title;
    var year = "";
    if (tagTitle && tagTitle !== name && tagTitle !== name.replace(/\.[^.]+$/, "")) {
      title = tagTitle;
    } else {
      var guessed = guessTitle(name);
      title = guessed.title;
      year = guessed.year || "";
    }
    return {
      id: id,
      kind: kind,
      name: name,
      title: title,
      year: year,
      uri: uri,
      thumbnail: thumbnail || "",
      duration: duration || 0
    };
  }

  function byTitle(a, b) {
    var x = a.title.toLowerCase();
    var y = b.title.toLowerCase();
    return x < y ? -1 : x > y ? 1 : 0;
  }

  // --- Source 1: the TV's media index ---------------------------------------------------

  function fromContentIndex(onDone) {
    if (!tizen.content || !tizen.content.find) {
      onDone("media index not available on this TV", { video: [], audio: [] }, { video: "", audio: "" });
      return;
    }
    var found = { video: [], audio: [] };
    var errors = { video: "", audio: "" };
    var pending = 2;

    function load(type, kind) {
      function finish() {
        pending -= 1;
        if (pending === 0) onDone(null, found, errors);
      }
      try {
        tizen.content.find(
          function (contents) {
            found[kind] = contents.map(function (c) {
              var thumb = c.thumbnailURIs && c.thumbnailURIs.length ? c.thumbnailURIs[0] : "";
              return makeItem(kind, c.id, c.name || "", c.title || "", c.contentURI, thumb, c.duration);
            });
            finish();
          },
          function (err) {
            errors[kind] = errorText(err);
            finish();
          },
          null,
          new tizen.AttributeFilter("type", "EXACTLY", type)
        );
      } catch (e) {
        errors[kind] = errorText(e);
        finish();
      }
    }

    load("VIDEO", "video");
    load("AUDIO", "audio");
  }

  // --- Source 2: walk mounted USB drives directly -----------------------------------------

  function fromUsbDrives(onDone) {
    var found = { video: [], audio: [] };
    if (!tizen.filesystem || !tizen.filesystem.listStorages) {
      onDone("file access not available on this TV", found);
      return;
    }
    var pending = 0;
    var finished = false;

    function settle() {
      pending -= 1;
      if (pending === 0 && !finished) {
        finished = true;
        onDone(null, found);
      }
    }

    function walk(dir, depth) {
      pending += 1;
      dir.listFiles(
        function (files) {
          for (var i = 0; i < files.length; i++) {
            var f = files[i];
            if (!f.name || f.name.charAt(0) === ".") continue;
            if (f.isDirectory) {
              if (depth < MAX_FOLDER_DEPTH) walk(f, depth + 1);
              continue;
            }
            var kind = kindOf(f.name);
            if (!kind) continue;
            found[kind].push(makeItem(kind, f.fullPath, f.name, "", f.toURI(), "", 0));
          }
          settle();
        },
        function () { settle(); }
      );
    }

    try {
      tizen.filesystem.listStorages(
        function (storages) {
          var roots = storages.filter(function (s) {
            return s.type === "EXTERNAL" && s.state === "MOUNTED";
          });
          if (!roots.length) {
            onDone(null, found);
            return;
          }
          roots.forEach(function (storage) {
            pending += 1;
            tizen.filesystem.resolve(
              storage.label,
              function (dir) {
                walk(dir, 0);
                settle();
              },
              function () { settle(); },
              "r"
            );
          });
        },
        function (err) { onDone(errorText(err), found); }
      );
    } catch (e) {
      onDone(errorText(e), found);
    }
  }

  function loadLibrary() {
    if (!window.tizen) {
      setStatus("This app needs to run on a Samsung TV (the tizen API isn't available here).");
      return;
    }
    setStatus("Loading your media…");

    fromContentIndex(function (indexUnavailable, indexed, indexErrors) {
      var indexedCount = indexed.video.length + indexed.audio.length;
      if (indexedCount > 0) {
        allItems = indexed;
        loadErrors = indexErrors;
        allItems.video.sort(byTitle);
        allItems.audio.sort(byTitle);
        render();
        return;
      }
      // Nothing from the index (unavailable, refused, or just hasn't indexed the drive):
      // look on the USB drives ourselves before giving up.
      fromUsbDrives(function (scanError, scanned) {
        allItems = scanned;
        allItems.video.sort(byTitle);
        allItems.audio.sort(byTitle);
        if (scanned.video.length + scanned.audio.length > 0) {
          loadErrors = { video: "", audio: "" };
        } else {
          var reason = indexUnavailable || scanError || "";
          loadErrors = {
            video: indexErrors.video || reason,
            audio: indexErrors.audio || reason
          };
        }
        render();
      });
    });
  }

  // --- Rendering -----------------------------------------------------------------------

  function createCard(item) {
    var card = document.createElement("button");
    card.className = "card";
    card.id = "item-" + item.id;

    var art = document.createElement("div");
    art.className = "card-art " + item.kind;
    if (item.thumbnail) {
      var img = document.createElement("img");
      img.src = item.thumbnail;
      img.alt = "";
      img.onerror = function () {
        if (img.parentNode) img.parentNode.removeChild(img);
      };
      art.appendChild(img);
    }
    card.appendChild(art);

    var title = document.createElement("div");
    title.className = "card-title";
    title.textContent = item.title;
    card.appendChild(title);

    var metaParts = [];
    if (item.year) metaParts.push(item.year);
    var dur = formatDuration(item.duration);
    if (dur) metaParts.push(dur);
    if (metaParts.length) {
      var meta = document.createElement("div");
      meta.className = "card-meta";
      meta.textContent = metaParts.join(" · ");
      card.appendChild(meta);
    }

    card.addEventListener("click", function () { play(item); });
    return card;
  }

  function render() {
    var items = allItems[currentKind];
    grid.innerHTML = "";
    if (loadErrors[currentKind] && !items.length) {
      setStatus("Couldn't read your " + currentKind + " files: " + loadErrors[currentKind]);
    } else if (!items.length) {
      setStatus(
        currentKind === "video"
          ? "No videos found. Plug in a USB drive with video files, then reopen the app."
          : "No audio found. Plug in a USB drive with music, then reopen the app."
      );
    } else {
      setStatus("");
    }
    if (!items.length) return;

    var fragment = document.createDocumentFragment();
    items.forEach(function (item) { fragment.appendChild(createCard(item)); });
    grid.appendChild(fragment);

    var restore = lastFocusedId && document.getElementById(lastFocusedId);
    (restore || grid.firstChild).focus();
  }

  function selectKind(kind) {
    currentKind = kind;
    lastFocusedId = null;
    var tabs = document.querySelectorAll(".tab");
    for (var i = 0; i < tabs.length; i++) {
      if (tabs[i].getAttribute("data-kind") === kind) tabs[i].classList.add("active");
      else tabs[i].classList.remove("active");
    }
    grid.scrollTop = 0;
    render();
  }

  // --- Playback ------------------------------------------------------------------------

  function isPlayerOpen() {
    return !playerView.classList.contains("hidden");
  }

  function activePlayer() {
    return videoPlayer.classList.contains("hidden") ? audioPlayer : videoPlayer;
  }

  function play(item) {
    lastFocusedId = "item-" + item.id;
    var player = item.kind === "video" ? videoPlayer : audioPlayer;
    var other = item.kind === "video" ? audioPlayer : videoPlayer;
    other.classList.add("hidden");
    player.classList.remove("hidden");

    nowPlaying.textContent = item.title;
    player.src = item.uri;
    libraryView.classList.add("hidden");
    playerView.classList.remove("hidden");
    var started = player.play();
    if (started && started.catch) started.catch(function () {});
    player.focus();
  }

  function stopPlayback() {
    [videoPlayer, audioPlayer].forEach(function (p) {
      p.pause();
      p.removeAttribute("src");
      p.load();
      p.classList.add("hidden");
    });
    playerView.classList.add("hidden");
    libraryView.classList.remove("hidden");
    var restore = lastFocusedId && document.getElementById(lastFocusedId);
    if (restore) restore.focus();
  }

  function togglePlay() {
    var p = activePlayer();
    if (p.paused) p.play();
    else p.pause();
  }

  function seekBy(seconds) {
    var p = activePlayer();
    if (!isFinite(p.duration)) return;
    p.currentTime = Math.max(0, Math.min(p.duration, p.currentTime + seconds));
  }

  function exitApp() {
    try {
      tizen.application.getCurrentApplication().exit();
    } catch (e) {
      // Not on a TV (e.g. a desktop browser preview) — nothing to exit.
    }
  }

  // --- Remote control ------------------------------------------------------------------

  // Moves focus to the nearest visible focusable element in the pressed direction, by on-screen
  // position — so it works the same for the tab row, any number of grid columns, and the player
  // header, without hard-coding a layout. Samsung's TV browser doesn't reliably do this on its own.
  function moveFocus(direction) {
    var root = isPlayerOpen() ? playerView : libraryView;
    var candidates = root.querySelectorAll("button, video:not(.hidden), audio:not(.hidden)");
    var current = document.activeElement;
    if (!current || !root.contains(current) || current === document.body) {
      if (candidates.length) candidates[0].focus();
      return;
    }
    var from = current.getBoundingClientRect();
    var fx = from.left + from.width / 2;
    var fy = from.top + from.height / 2;
    var best = null;
    var bestScore = Infinity;
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      if (el === current) continue;
      var r = el.getBoundingClientRect();
      if (!r.width || !r.height) continue;
      var dx = r.left + r.width / 2 - fx;
      var dy = r.top + r.height / 2 - fy;
      var primary, secondary;
      if (direction === "left") { primary = -dx; secondary = dy; }
      else if (direction === "right") { primary = dx; secondary = dy; }
      else if (direction === "up") { primary = -dy; secondary = dx; }
      else { primary = dy; secondary = dx; }
      if (primary <= 1) continue;
      var score = primary + Math.abs(secondary) * 2;
      if (score < bestScore) {
        bestScore = score;
        best = el;
      }
    }
    if (best) {
      best.focus();
      if (best.scrollIntoView) best.scrollIntoView(false);
    }
  }

  // Media keys aren't delivered to a Tizen web app at all unless registered first.
  function registerMediaKeys() {
    if (!window.tizen || !tizen.tvinputdevice) return;
    ["MediaPlayPause", "MediaPlay", "MediaPause", "MediaStop", "MediaFastForward", "MediaRewind"]
      .forEach(function (name) {
        try {
          tizen.tvinputdevice.registerKey(name);
        } catch (e) {
          // Key not on this remote — the on-screen controls still work.
        }
      });
  }

  document.addEventListener("keydown", function (e) {
    var code = e.keyCode;

    // Remote "Return" closes the player if it's open, otherwise exits the app — without this
    // handler Return does nothing at all on a Tizen web app.
    if (code === KEY.BACK) {
      e.preventDefault();
      if (isPlayerOpen()) stopPlayback();
      else exitApp();
      return;
    }

    if (isPlayerOpen()) {
      var onMedia = document.activeElement === activePlayer();
      if (code === KEY.PLAY_PAUSE || (code === KEY.ENTER && onMedia)) { e.preventDefault(); togglePlay(); return; }
      if (code === KEY.PLAY) { e.preventDefault(); activePlayer().play(); return; }
      if (code === KEY.PAUSE) { e.preventDefault(); activePlayer().pause(); return; }
      if (code === KEY.STOP) { e.preventDefault(); stopPlayback(); return; }
      if (code === KEY.FAST_FORWARD || (code === KEY.RIGHT && onMedia)) { e.preventDefault(); seekBy(10); return; }
      if (code === KEY.REWIND || (code === KEY.LEFT && onMedia)) { e.preventDefault(); seekBy(-10); return; }
    }

    var direction = DIRECTIONS[code];
    if (direction) {
      e.preventDefault();
      moveFocus(direction);
    }
  });

  document.getElementById("tab-video").addEventListener("click", function () { selectKind("video"); });
  document.getElementById("tab-audio").addEventListener("click", function () { selectKind("audio"); });
  backButton.addEventListener("click", stopPlayback);
  [videoPlayer, audioPlayer].forEach(function (p) {
    p.addEventListener("error", function () {
      if (!p.getAttribute("src")) return;
      nowPlaying.textContent = nowPlaying.textContent + " — this TV can't play this file";
    });
  });

  window.addEventListener("load", function () {
    registerMediaKeys();
    loadLibrary();
  });
})();
