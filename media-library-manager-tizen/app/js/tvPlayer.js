/**
 * Full-screen live-TV playback for a Fizz channel on a Samsung TV.
 *
 * Shaka Player loads the live DASH stream and drives the TV's built-in Widevine CDM through EME —
 * no runtime swap, just the browser's own DRM. For a protected channel the Widevine licence request
 * is wrapped in Fizz's Vualto envelope (the account token, the raw request bytes and the manifest's
 * default_kid) and posted to the licence proxy — exactly what the Android and Windows builds do.
 * Clear channels play without DRM. Plain ES5, like the rest of the app.
 */
var TvPlayer = (function () {
  var LICENSE_URL = "https://widevine-proxy.drm.technology/proxy";

  var overlay = document.getElementById("tv-player");
  var video = document.getElementById("tv-player-video");
  var statusEl = document.getElementById("tv-player-status");
  var player = null;        // the Shaka player, while open
  var closeHandler = null;  // called when the overlay closes
  var open = false;

  function isOpen() { return open; }

  function setStatus(text) {
    statusEl.textContent = text || "";
    statusEl.style.display = text ? "block" : "none";
  }

  function toByteArray(buffer) {
    var bytes = new Uint8Array(buffer);
    var out = [];
    for (var i = 0; i < bytes.length; i++) out.push(bytes[i]);
    return out;
  }

  /** The DASH manifest's default_kid, which the Vualto licence envelope needs. done(kid|null). */
  function fetchKid(manifestUrl, done) {
    try {
      var xhr = new XMLHttpRequest();
      xhr.open("GET", manifestUrl, true);
      xhr.timeout = 15000;
      xhr.onload = function () {
        var m = /default_kid="(.*?)"/i.exec(xhr.responseText || "");
        done(m ? m[1] : null);
      };
      xhr.onerror = function () { done(null); };
      xhr.ontimeout = function () { done(null); };
      xhr.send();
    } catch (e) {
      done(null);
    }
  }

  function drmMessage(code) {
    // Shaka error codes 6000–6999 are DRM/EME.
    if (code && code >= 6000 && code < 7000) return "This TV couldn't get a licence for the channel.";
    if (code === 1001 || code === 1002) return "Couldn't load the channel stream.";
    return "Playback failed.";
  }

  /** stream: { url, isDash, drmToken, licenseUrl }. channelName for the status line. */
  function play(stream, channelName) {
    setStatus("Opening " + channelName + "…");
    if (typeof shaka === "undefined" || !shaka.Player) {
      setStatus("This TV can't play live video (no player support).");
      return;
    }
    shaka.polyfill.installAll();
    player = new shaka.Player();
    player.attach(video).then(function () {
      player.addEventListener("error", function (e) {
        var code = e && e.detail ? e.detail.code : undefined;
        setStatus("Can't play " + channelName + ". " + drmMessage(code));
      });
      start(stream, channelName);
    });
  }

  function start(stream, channelName) {
    function loadNow(kid) {
      if (stream.drmToken) {
        player.configure({ drm: { servers: { "com.widevine.alpha": stream.licenseUrl || LICENSE_URL } } });
        player.getNetworkingEngine().registerRequestFilter(function (type, request) {
          if (type !== shaka.net.NetworkingEngine.RequestType.LICENSE) return;
          var challenge = toByteArray(request.body);
          request.headers["Content-Type"] = "application/json";
          var body = JSON.stringify({ token: stream.drmToken, drm_info: challenge, kid: kid || "" });
          request.body = new TextEncoder().encode(body);
        });
      }
      player.load(stream.url).then(function () {
        setStatus("");
        var started = video.play();
        if (started && started.catch) started.catch(function () {});
        video.focus();
      }).catch(function (e) {
        setStatus("Can't play " + channelName + ". " + drmMessage(e && e.code));
      });
    }
    if (stream.drmToken && stream.isDash) fetchKid(stream.url, loadNow);
    else loadNow(null);
  }

  /** stream from FizzTv.streamForChannel; onClose runs after the overlay is torn down. */
  function openStream(stream, channelName, onClose) {
    closeHandler = onClose || null;
    open = true;
    overlay.classList.remove("hidden");
    setStatus("Opening " + channelName + "…");
    play(stream, channelName);
  }

  function close() {
    if (!open) return;
    open = false;
    overlay.classList.add("hidden");
    setStatus("");
    try { video.pause(); } catch (e) {}
    if (player) {
      var p = player;
      player = null;
      p.destroy().catch(function () {});
    }
    try { video.removeAttribute("src"); video.load(); } catch (e2) {}
    var cb = closeHandler;
    closeHandler = null;
    if (cb) cb();
  }

  return { openStream: openStream, close: close, isOpen: isOpen };
})();
