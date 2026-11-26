/**
 * Fizz TV client for the TV tab: sign-in with a code (Okta device flow, the same as the Fizz TV
 * app) and the guide for the channels in your plan. Mirrors media-library-manager/src/main/tv/
 * fizzTv.ts, in plain ES5 with callbacks so it runs on 2017+ Samsung TVs.
 *
 * The saved login (refresh token, session id, email, device id) is kept in localStorage. Like
 * the Fizz app: a network error never wipes it, an expired session is renewed with the refresh
 * token (one renewal at a time), and only a refresh token the server rejects signs you out.
 */
var FizzTv = (function () {
  var config = {
    oktaBase: "https://auth.fizz.ca/oauth2/aus4ah1q96x2bjArV697/v1",
    apiBase: "https://a2.vmedia.ca",
    deviceClientId: "0oa4agzobqaFHJ1ob697",
    scope: "openid email profile offline_access",
    appName: "F-GOOGLE-PLAY-MOBILE",
    siteId: 176,
    imageBase: "https://images.cdn.vmedia.ca/api/images"
  };
  // Tests point the client at a fake server.
  if (window.FIZZ_CONFIG_OVERRIDE) {
    for (var k in window.FIZZ_CONFIG_OVERRIDE) config[k] = window.FIZZ_CONFIG_OVERRIDE[k];
  }

  var STORE_KEY = "omniplay.fizzTv";
  var DEVICE_KEY = "omniplay.fizzTv.deviceId";
  var PAGE_SIZE = 500; // the service's maximum
  var MAX_PAGES = 10;
  var channelCache = null;
  var renewing = null; // callbacks waiting for the renewal in progress

  // ---- Storage ----

  function load() {
    try {
      var raw = localStorage.getItem(STORE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;
    }
  }

  function save(creds) {
    try {
      if (creds) localStorage.setItem(STORE_KEY, JSON.stringify(creds));
      else localStorage.removeItem(STORE_KEY);
    } catch (e) {}
  }

  function deviceId() {
    var id = null;
    try { id = localStorage.getItem(DEVICE_KEY); } catch (e) {}
    if (!id) {
      id = "";
      for (var i = 0; i < 16; i++) id += "0123456789abcdef".charAt(Math.floor(Math.random() * 16));
      try { localStorage.setItem(DEVICE_KEY, id); } catch (e) {}
    }
    return id;
  }

  // ---- HTTP ----

  function SignInRequired(message) {
    this.message = message || "Sign in to your Fizz account.";
    this.signInRequired = true;
  }

  function request(method, url, opts, done) {
    var xhr = new XMLHttpRequest();
    xhr.open(method, url, true);
    xhr.timeout = 30000;
    xhr.setRequestHeader("Accept", "application/json");
    var headers = opts.headers || {};
    for (var h in headers) xhr.setRequestHeader(h, headers[h]);
    var body = null;
    if (opts.json !== undefined) {
      xhr.setRequestHeader("Content-Type", "application/json; charset=utf-8");
      body = JSON.stringify(opts.json);
    } else if (opts.form !== undefined) {
      xhr.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
      body = opts.form;
    }
    xhr.onload = function () { done(null, xhr.status, xhr.responseText); };
    xhr.onerror = function () { done({ message: "Can't reach the TV service. Check the TV's internet connection." }); };
    xhr.ontimeout = xhr.onerror;
    xhr.send(body);
  }

  function form(fields) {
    var parts = [];
    for (var k in fields) parts.push(encodeURIComponent(k) + "=" + encodeURIComponent(fields[k]));
    return parts.join("&");
  }

  function parse(text) {
    try { return JSON.parse(text); } catch (e) { return {}; }
  }

  // ---- Platform login (session) ----

  function settingValue(settings, key) {
    if (!settings) return null;
    for (var i = 0; i < settings.length; i++) if (settings[i] && settings[i].key === key) return settings[i].value || null;
    return null;
  }

  /** opts: accessToken | refreshToken, oktaRefreshToken, deviceId. done(err, sessionId) */
  function login(opts, done) {
    var device = opts.deviceId || (load() && load().deviceId) || deviceId();
    var body = { cc: { clientCredentials: { StbMacAddress: device }, appSettings: { appName: config.appName, siteID: config.siteId } } };
    if (opts.accessToken) body.AccessToken = opts.accessToken;
    if (opts.refreshToken) body.RefreshToken = opts.refreshToken;
    request("POST", config.apiBase + "/api/client/login/okta", { json: body }, function (err, status, text) {
      if (err) return done(err);
      if (status >= 400) return done({ message: "Couldn't sign in to the TV service (" + status + ")." });
      var r = parse(text);
      var settings = r.appSettings && r.appSettings.settings;
      // Every reply carries a status; "OK" ("You have successfully logged in…") is success.
      var code = settingValue(settings, "errorCode");
      if (code && code.toUpperCase() !== "OK") {
        if (opts.refreshToken && /REFRESH_TOKEN/i.test(code)) {
          signOut();
          return done(new SignInRequired("Your Fizz sign-in expired. Please sign in again."));
        }
        return done({ message: settingValue(settings, "errorMessage") || code });
      }
      var creds = r.clientCredentials || {};
      if (!creds.sessionID) return done({ message: "The TV service didn't start a session." });
      // Like the Fizz app: the first sign-in keeps Okta's own refresh token.
      var refresh = r.RefreshToken || creds.RefreshToken || opts.refreshToken || opts.oktaRefreshToken;
      if (!refresh) return done({ message: "The TV service didn't return a saved login." });
      var saved = load();
      save({
        refreshToken: refresh,
        sessionId: creds.sessionID,
        email: creds.Email || creds.UserLogin || (saved && saved.email) || null,
        deviceId: device
      });
      done(null, creds.sessionID);
    });
  }

  /** One renewal at a time: Fizz replaces the refresh token on each one. */
  function renew(failedSession, done) {
    if (renewing) { renewing.push(done); return; }
    var current = load();
    if (!current) return done(new SignInRequired());
    if (current.sessionId && current.sessionId !== failedSession) return done(null, current.sessionId);
    renewing = [done];
    login({ refreshToken: current.refreshToken, deviceId: current.deviceId }, function (err, session) {
      var waiting = renewing;
      renewing = null;
      for (var i = 0; i < waiting.length; i++) waiting[i](err, session);
    });
  }

  /** call(session, cb(err, status, text)); renews the session once if the server refuses it. */
  function withSession(call, done) {
    var saved = load();
    if (!saved) return done(new SignInRequired());
    function attempt(session, retried) {
      call(session, function (err, status, text) {
        if (err) return done(err);
        if (status === 401 || status === 403 || status === 500) {
          if (retried) return done({ message: "The TV service didn't answer (" + status + "). Try again in a moment." });
          return renew(session, function (e, renewed) {
            if (e) return done(e);
            attempt(renewed, true);
          });
        }
        if (status >= 400) return done({ message: "The TV service refused the request (HTTP " + status + ")." });
        done(null, text);
      });
    }
    if (saved.sessionId) attempt(saved.sessionId, false);
    else renew("", function (e, s) { if (e) return done(e); attempt(s, true); });
  }

  // ---- Sign-in with a code ----

  function startSignIn(done) {
    request("POST", config.oktaBase + "/device/authorize", { form: form({ client_id: config.deviceClientId, scope: config.scope }) }, function (err, status, text) {
      if (err) return done(err);
      var r = parse(text);
      if (!r.device_code) return done({ message: r.error_description || r.error || "Couldn't get a sign-in code." });
      done(null, {
        userCode: r.user_code,
        verificationUri: r.verification_uri,
        deviceCode: r.device_code,
        interval: Math.max(1, r.interval || 5),
        expiresAt: Date.now() + (r.expires_in || 600) * 1000
      });
    });
  }

  /** done(err, signedIn): signedIn false while still waiting for you to approve the code. */
  function pollSignIn(code, done) {
    if (Date.now() > code.expiresAt) return done({ message: "The code expired. Get a new one." });
    request("POST", config.oktaBase + "/token", {
      form: form({ client_id: config.deviceClientId, grant_type: "urn:ietf:params:oauth:grant-type:device_code", device_code: code.deviceCode })
    }, function (err, status, text) {
      if (err) return done(err);
      var r = parse(text);
      if (r.access_token) {
        return login({ accessToken: r.access_token, oktaRefreshToken: r.refresh_token }, function (e) {
          if (e) return done(e);
          done(null, true);
        });
      }
      if (r.error === "authorization_pending" || r.error === "slow_down") return done(null, false);
      if (r.error === "expired_token") return done({ message: "The code expired. Get a new one." });
      if (r.error === "access_denied") return done({ message: "Sign-in was declined." });
      done({ message: r.error_description || r.error || "Sign-in failed." });
    });
  }

  function signOut() {
    channelCache = null;
    save(null);
  }

  // ---- Guide ----

  function toInt(v) {
    if (v === null || v === undefined || v === "") return null;
    var n = Number(v);
    return isFinite(n) ? Math.floor(n) : null;
  }

  function imageUrl(images, types) {
    if (!images || !images.length) return null;
    for (var t = 0; t < types.length; t++) {
      for (var i = 0; i < images.length; i++) {
        if (images[i] && Number(images[i].Type) === types[t]) return config.imageBase + "?id=" + images[i].Id + "&t=" + images[i].Type;
      }
    }
    return null;
  }

  /** ISO time; one without a zone is UTC (the guide is asked for in UTC). */
  function parseTime(text) {
    if (typeof text !== "string" || !text) return NaN;
    return Date.parse(/(Z|[+-]\d\d:?\d\d)$/i.test(text) ? text : text + "Z");
  }

  /** The channels in your plan ("My plan"), in channel-number order. done(err, channels) */
  function channels(forceRefresh, done) {
    if (!forceRefresh && channelCache) return done(null, channelCache);
    withSession(function (session, cb) {
      request("POST", config.apiBase + "/api/channel/client", {
        json: {
          request: {
            type: "4",
            filter: { contentType: "4", availableOnly: true, HasImagesOnly: false },
            paging: { itemsOnPage: 1000, pageNumber: 1 },
            UseUtcTime: true
          },
          sessionID: session
        }
      }, cb);
    }, function (err, text) {
      if (err) return done(err);
      var items = parse(text).items || [];
      var seen = {};
      var list = [];
      for (var i = 0; i < items.length; i++) {
        var c = items[i];
        if (c.visible === false || seen[c.id]) continue;
        seen[c.id] = true;
        list.push({
          id: Number(c.id),
          number: toInt(c.accessNum) !== null ? toInt(c.accessNum) : toInt(c.orderNum),
          name: String(c.name || c.CallSign || ""),
          logoUrl: imageUrl(c.Images, [8, 4, 10])
        });
      }
      list.sort(function (a, b) {
        var x = a.number === null ? Infinity : a.number;
        var y = b.number === null ? Infinity : b.number;
        return x - y || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
      });
      channelCache = list;
      done(null, list);
    });
  }

  /** Programs on these channels between from and to (epoch ms). done(err, programs) */
  function guide(channelIds, from, to, done) {
    var ids = channelIds.slice().sort(function (a, b) { return a - b; }).join(",");
    var items = [];
    function page(n) {
      var query = form({ ChannelIds: ids, StartDate: String(from), EndDate: String(to), PageNumber: String(n), PageSize: String(PAGE_SIZE) });
      withSession(function (session, cb) {
        request("GET", config.apiBase + "/api/v3/guide/client?" + query, { headers: { "X-Session-Id": session } }, cb);
      }, function (err, text) {
        if (err) return done(err);
        var pageItems = parse(text).items || [];
        items = items.concat(pageItems);
        if (pageItems.length === PAGE_SIZE && n < MAX_PAGES) return page(n + 1);
        done(null, toPrograms(items, from, to));
      });
    }
    page(1);
  }

  function toPrograms(items, from, to) {
    var seen = {};
    var out = [];
    for (var i = 0; i < items.length; i++) {
      var p = items[i];
      var air = p.recentAirTime;
      var channelId = air ? toInt(air.channelID) : null;
      var start = air ? parseTime(air.startTime) : NaN;
      if (channelId === null || isNaN(start)) continue;
      var end = start + Math.max(1, toInt(p.length) || 0) * 60000;
      var series = p.SeriesName || null;
      var name = p.name || p.shortName || "";
      var season = toInt(p.SeasonNumber);
      var ep = toInt(p.episodeNum);
      var parts = [];
      if (season !== null && ep !== null) parts.push("S" + season + " E" + ep);
      if (series && name && name !== series) parts.push(name);
      var key = (air.id || 0) + "-" + (p.id || 0) + "-" + start;
      if (end <= from || start >= to || seen[key]) continue;
      seen[key] = true;
      out.push({
        key: key,
        channelId: channelId,
        title: series || name || "—",
        episode: parts.length ? parts.join(" · ") : null,
        description: p.description || p.shortDescription || null,
        start: start,
        end: end
      });
    }
    out.sort(function (a, b) { return a.start - b.start; });
    return out;
  }

  function credentials() {
    return load();
  }

  return {
    credentials: credentials,
    startSignIn: startSignIn,
    pollSignIn: pollSignIn,
    signOut: signOut,
    channels: channels,
    guide: guide,
    parseTime: parseTime
  };
})();
