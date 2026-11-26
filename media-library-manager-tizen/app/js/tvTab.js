/**
 * The TV tab: sign in with a code shown on screen, then the Fizz "My plan" guide.
 *
 * The guide shows a 3-hour window. With the remote: Up/Down moves between channels (keeping the
 * same time), Left/Right between shows (the window follows), OK opens a show's details, Return
 * closes them / goes back to the tabs. Plain ES5 like the rest of the app.
 */
var TvTab = (function () {
  var WINDOW_MS = 3 * 3600000;
  var HOUR = 3600000;
  var DAY = 24 * HOUR;

  var view = document.getElementById("tv-view");
  var signInBox = document.getElementById("tv-signin");
  var signInText = document.getElementById("tv-signin-text");
  var codeBox = document.getElementById("tv-code");
  var codeUrl = document.getElementById("tv-code-url");
  var codeValue = document.getElementById("tv-code-value");
  var signInError = document.getElementById("tv-signin-error");
  var signInButton = document.getElementById("tv-signin-button");
  var guideBox = document.getElementById("tv-guide");
  var daysBar = document.getElementById("tv-days");
  var emailEl = document.getElementById("tv-email");
  var guideStatus = document.getElementById("tv-status");
  var timeline = document.getElementById("tv-timeline");
  var rowsEl = document.getElementById("tv-rows");
  var details = document.getElementById("tv-details");

  var channels = [];
  var programs = {}; // channelId -> [program]
  var dayOffset = 0;
  var dayStart = 0;
  var winStart = 0;
  var loadId = 0;
  var pollTimer = null;
  var pendingCode = null;
  var detailsReturnFocus = null;

  function startOfDay(offset) {
    var d = new Date();
    d.setHours(0, 0, 0, 0);
    d.setDate(d.getDate() + offset);
    return d.getTime();
  }

  function two(n) { return n < 10 ? "0" + n : String(n); }

  function clock(ms) {
    var d = new Date(ms);
    var h = d.getHours();
    var suffix = h < 12 ? "AM" : "PM";
    var h12 = h % 12 === 0 ? 12 : h % 12;
    return h12 + ":" + two(d.getMinutes()) + " " + suffix;
  }

  var DAY_NAMES = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  function dayLabel(offset) {
    if (offset === 0) return "Today";
    if (offset === -1) return "Yesterday";
    if (offset === 1) return "Tomorrow";
    var d = new Date(startOfDay(offset));
    return DAY_NAMES[d.getDay()] + " " + d.getDate();
  }

  function clearChildren(el) {
    while (el.firstChild) el.removeChild(el.firstChild);
  }

  // ---- Showing the tab ----

  /** Called when the TV tab is selected. */
  function show() {
    view.classList.remove("hidden");
    if (FizzTv.credentials()) {
      showGuide();
    } else {
      showSignIn(null);
    }
  }

  function hide() {
    view.classList.add("hidden");
    stopPolling();
  }

  function isActive() {
    return !view.classList.contains("hidden");
  }

  /** The first thing to focus when moving down from the tabs. */
  function firstFocusable() {
    if (!signInBox.classList.contains("hidden")) return signInButton;
    return daysBar.querySelector(".tv-day.active") || daysBar.firstChild;
  }

  // ---- Sign-in ----

  function showSignIn(error) {
    guideBox.classList.add("hidden");
    signInBox.classList.remove("hidden");
    codeBox.classList.add("hidden");
    signInText.classList.remove("hidden");
    signInButton.textContent = "Sign in with a code";
    signInError.textContent = error || "";
    pendingCode = null;
  }

  function startSignIn() {
    stopPolling();
    signInError.textContent = "";
    signInButton.textContent = "Getting a code…";
    FizzTv.startSignIn(function (err, code) {
      if (err) return showSignIn(err.message);
      pendingCode = code;
      codeUrl.textContent = String(code.verificationUri || "").replace(/^https?:\/\//, "");
      codeValue.textContent = code.userCode;
      codeBox.classList.remove("hidden");
      signInText.classList.add("hidden");
      signInButton.textContent = "Get a new code";
      poll(code);
    });
  }

  function poll(code) {
    pollTimer = setTimeout(function () {
      FizzTv.pollSignIn(code, function (err, done) {
        if (pendingCode !== code) return;
        if (err) return showSignIn(err.message);
        if (done) {
          pendingCode = null;
          showGuide();
          var first = firstFocusable();
          if (first) first.focus();
        } else {
          poll(code);
        }
      });
    }, code.interval * 1000);
  }

  function stopPolling() {
    if (pollTimer) clearTimeout(pollTimer);
    pollTimer = null;
  }

  // ---- Guide ----

  function showGuide() {
    stopPolling();
    signInBox.classList.add("hidden");
    guideBox.classList.remove("hidden");
    var creds = FizzTv.credentials();
    emailEl.textContent = (creds && creds.email) || "";
    renderDays();
    if (!channels.length) loadDay(dayOffset, false);
  }

  function renderDays() {
    clearChildren(daysBar);
    for (var o = -1; o <= 6; o++) {
      var b = document.createElement("button");
      b.className = "tv-day" + (o === dayOffset ? " active" : "");
      b.textContent = dayLabel(o);
      b.setAttribute("data-offset", String(o));
      b.addEventListener("click", (function (offset) { return function () { loadDay(offset, false); }; })(o));
      daysBar.appendChild(b);
    }
    var refresh = document.createElement("button");
    refresh.className = "tv-action";
    refresh.textContent = "Refresh";
    refresh.addEventListener("click", function () { loadDay(dayOffset, true); });
    daysBar.appendChild(refresh);
    var out = document.createElement("button");
    out.className = "tv-action";
    out.textContent = "Sign out";
    out.addEventListener("click", function () {
      FizzTv.signOut();
      channels = [];
      programs = {};
      showSignIn(null);
      signInButton.focus();
    });
    daysBar.appendChild(out);
  }

  function setGuideStatus(text) {
    guideStatus.textContent = text || "";
    guideStatus.style.display = text ? "block" : "none";
  }

  function loadDay(offset, refresh) {
    var id = ++loadId;
    dayOffset = offset;
    dayStart = startOfDay(offset);
    // Today: start half an hour before now. Other days: the same time of day.
    var sinceMidnight = Date.now() - startOfDay(0);
    winStart = dayStart + Math.floor(sinceMidnight / 1800000) * 1800000 - 1800000;
    winStart = Math.max(dayStart, Math.min(winStart, dayStart + DAY - WINDOW_MS));
    programs = {};
    renderDays();
    var active = daysBar.querySelector(".tv-day.active");
    if (active && document.activeElement && document.activeElement.className.indexOf("tv-day") >= 0) active.focus();
    setGuideStatus("Loading…");
    FizzTv.channels(refresh, function (err, list) {
      if (id !== loadId) return;
      if (err) return fail(err);
      channels = list;
      renderGrid();
      if (!list.length) return setGuideStatus("No channels in your plan.");
      // A few channels per request, three requests at a time; rows fill in as they arrive.
      var chunks = [];
      for (var i = 0; i < list.length; i += 10) chunks.push(list.slice(i, i + 10));
      var next = 0;
      var running = 0;
      var failed = null;
      function pump() {
        while (running < 3 && next < chunks.length) {
          (function (chunk) {
            running++;
            FizzTv.guide(chunk.map(function (c) { return c.id; }), dayStart, dayStart + DAY, function (e, progs) {
              running--;
              if (id !== loadId) return;
              if (e) {
                if (e.signInRequired) return fail(e);
                failed = e;
              } else {
                for (var j = 0; j < chunk.length; j++) programs[chunk[j].id] = [];
                for (var k = 0; k < progs.length; k++) {
                  if (programs[progs[k].channelId]) programs[progs[k].channelId].push(progs[k]);
                }
                for (var r = 0; r < chunk.length; r++) renderRow(chunk[r].id);
              }
              if (running === 0 && next >= chunks.length) setGuideStatus(failed ? failed.message : "");
              else pump();
            });
          })(chunks[next++]);
        }
      }
      pump();
    });
  }

  function fail(err) {
    if (err.signInRequired) {
      channels = [];
      programs = {};
      showSignIn(err.message);
      signInButton.focus();
      return;
    }
    setGuideStatus(err.message || "Something went wrong.");
  }

  function pxPerMs() {
    return rowsEl.clientWidth > 0 ? (rowsEl.clientWidth - channelColumnWidth()) / WINDOW_MS : 0;
  }

  function channelColumnWidth() {
    return 220;
  }

  function renderTimeline() {
    clearChildren(timeline);
    var scale = pxPerMs();
    for (var t = winStart; t < winStart + WINDOW_MS; t += 1800000) {
      var label = document.createElement("span");
      label.className = "tv-tick";
      label.style.left = channelColumnWidth() + (t - winStart) * scale + "px";
      label.textContent = clock(t);
      timeline.appendChild(label);
    }
    var now = Date.now();
    if (now >= winStart && now < winStart + WINDOW_MS) {
      var line = document.createElement("div");
      line.className = "tv-now";
      line.style.left = channelColumnWidth() + (now - winStart) * scale + "px";
      timeline.appendChild(line);
    }
  }

  function renderGrid() {
    clearChildren(rowsEl);
    renderTimeline();
    for (var i = 0; i < channels.length; i++) {
      var c = channels[i];
      var row = document.createElement("div");
      row.className = "tv-row";
      row.id = "tv-row-" + c.id;
      row.setAttribute("data-index", String(i));
      var ch = document.createElement("div");
      ch.className = "tv-ch";
      if (c.logoUrl) {
        var img = document.createElement("img");
        img.src = c.logoUrl;
        img.alt = c.name;
        img.onerror = (function (holder, name) {
          return function () {
            holder.removeChild(this);
            var span = document.createElement("span");
            span.textContent = name;
            holder.insertBefore(span, holder.firstChild);
          };
        })(ch, c.name);
        ch.appendChild(img);
      } else {
        var nm = document.createElement("span");
        nm.textContent = c.name;
        ch.appendChild(nm);
      }
      if (c.number !== null) {
        var num = document.createElement("span");
        num.className = "tv-ch-num";
        num.textContent = String(c.number);
        ch.appendChild(num);
      }
      row.appendChild(ch);
      var progs = document.createElement("div");
      progs.className = "tv-progs";
      row.appendChild(progs);
      rowsEl.appendChild(row);
      renderRow(c.id);
    }
  }

  function renderRow(channelId) {
    var row = document.getElementById("tv-row-" + channelId);
    if (!row) return;
    var box = row.querySelector(".tv-progs");
    clearChildren(box);
    var list = programs[channelId];
    if (!list) {
      var wait = document.createElement("span");
      wait.className = "tv-none";
      wait.textContent = "Loading…";
      box.appendChild(wait);
      return;
    }
    var scale = pxPerMs();
    var winEnd = winStart + WINDOW_MS;
    var now = Date.now();
    var shown = 0;
    for (var i = 0; i < list.length; i++) {
      var p = list[i];
      if (p.end <= winStart || p.start >= winEnd) continue;
      var left = (Math.max(p.start, winStart) - winStart) * scale;
      var width = (Math.min(p.end, winEnd) - Math.max(p.start, winStart)) * scale;
      var b = document.createElement("button");
      b.className = "tv-prog" + (p.end <= now ? " past" : p.start <= now ? " live" : "");
      b.style.left = left + "px";
      b.style.width = Math.max(2, width - 4) + "px";
      b.setAttribute("data-key", p.key);
      b.setAttribute("data-channel", String(channelId));
      var t = document.createElement("span");
      t.className = "tv-prog-title";
      t.textContent = p.title;
      b.appendChild(t);
      var s = document.createElement("span");
      s.className = "tv-prog-sub";
      s.textContent = p.episode || clock(p.start) + " – " + clock(p.end);
      b.appendChild(s);
      b.addEventListener("click", (function (prog, cid) { return function () { openDetails(prog, cid); }; })(p, channelId));
      box.appendChild(b);
      shown++;
    }
    if (!shown) {
      var none = document.createElement("span");
      none.className = "tv-none";
      none.textContent = "No listings";
      box.appendChild(none);
    }
  }

  function rerenderAll() {
    renderTimeline();
    for (var i = 0; i < channels.length; i++) renderRow(channels[i].id);
  }

  function findProgram(channelId, key) {
    var list = programs[channelId] || [];
    for (var i = 0; i < list.length; i++) if (list[i].key === key) return { list: list, index: i };
    return null;
  }

  function focusProgram(channelId, key) {
    var row = document.getElementById("tv-row-" + channelId);
    if (!row) return false;
    var el = row.querySelector('.tv-prog[data-key="' + key + '"]');
    if (!el) return false;
    el.focus();
    if (el.scrollIntoView) el.scrollIntoView(false);
    return true;
  }

  /** Shows a program, moving the window if the show is outside it (or barely in it). */
  function reveal(channelId, prog) {
    var winEnd = winStart + WINDOW_MS;
    var visible = Math.min(prog.end, winEnd) - Math.max(prog.start, winStart);
    if (visible < 1800000 && visible < prog.end - prog.start) {
      winStart = Math.floor(prog.start / 1800000) * 1800000 - 1800000;
      winStart = Math.max(dayStart, Math.min(winStart, dayStart + DAY - WINDOW_MS));
      rerenderAll();
    }
    focusProgram(channelId, prog.key);
  }

  /** The show on a channel at a given time (or the nearest one). */
  function programAt(channelId, time) {
    var list = programs[channelId] || [];
    var best = null;
    var bestGap = Infinity;
    for (var i = 0; i < list.length; i++) {
      var p = list[i];
      if (p.end <= winStart || p.start >= winStart + WINDOW_MS) continue;
      if (p.start <= time && time < p.end) return p;
      var gap = Math.min(Math.abs(p.start - time), Math.abs(p.end - time));
      if (gap < bestGap) { bestGap = gap; best = p; }
    }
    return best;
  }

  // ---- Details ----

  function openDetails(prog, channelId) {
    detailsReturnFocus = document.activeElement;
    var ch = null;
    for (var i = 0; i < channels.length; i++) if (channels[i].id === channelId) ch = channels[i];
    details.querySelector(".tv-details-title").textContent = prog.title;
    details.querySelector(".tv-details-episode").textContent = prog.episode || "";
    var d = new Date(prog.start);
    details.querySelector(".tv-details-meta").textContent =
      (ch ? (ch.number !== null ? ch.number + " · " : "") + ch.name + " · " : "") +
      DAY_NAMES[d.getDay()] + " " + d.getDate() + " · " + clock(prog.start) + " – " + clock(prog.end);
    details.querySelector(".tv-details-text").textContent = prog.description || "";
    details.classList.remove("hidden");
    details.querySelector("button").focus();
  }

  function closeDetails() {
    details.classList.add("hidden");
    if (detailsReturnFocus) detailsReturnFocus.focus();
  }

  // ---- Remote ----

  /** Handles a key while the TV tab is showing. Returns true if it used it. */
  function handleKey(code, keys, header) {
    if (!details.classList.contains("hidden")) {
      if (code === keys.BACK || code === keys.ENTER) { closeDetails(); return true; }
      return true; // keep focus inside the details box
    }
    var el = document.activeElement;
    var inGuide = el && el.className && String(el.className).indexOf("tv-prog") === 0;
    if (code === keys.BACK) {
      // Return goes back up to the tabs; from the tabs it closes the app (handled by app.js).
      if (view.contains(el)) {
        var tab = header.querySelector(".tab.active");
        if (tab) tab.focus();
        return true;
      }
      return false;
    }
    if (!inGuide) {
      // From the day buttons, Down goes into the guide at the current time.
      if (code === keys.DOWN && el && daysBar.contains(el) && channels.length) {
        var first = programAt(channels[0].id, Math.max(Date.now(), winStart));
        if (first) { focusProgram(channels[0].id, first.key); return true; }
      }
      return false;
    }
    var channelId = Number(el.getAttribute("data-channel"));
    var found = findProgram(channelId, el.getAttribute("data-key"));
    if (!found) return false;
    var prog = found.list[found.index];
    var rowIndex = -1;
    for (var i = 0; i < channels.length; i++) if (channels[i].id === channelId) rowIndex = i;

    if (code === keys.RIGHT || code === keys.LEFT) {
      var nextIndex = found.index + (code === keys.RIGHT ? 1 : -1);
      var target = found.list[nextIndex];
      if (target) reveal(channelId, target);
      return true;
    }
    if (code === keys.UP || code === keys.DOWN) {
      var time = Math.max(prog.start, winStart);
      var r = rowIndex + (code === keys.DOWN ? 1 : -1);
      if (r < 0) {
        var day = daysBar.querySelector(".tv-day.active");
        if (day) day.focus();
        return true;
      }
      // Skip rows with nothing in the window.
      while (r >= 0 && r < channels.length) {
        var p = programAt(channels[r].id, time);
        if (p) { focusProgram(channels[r].id, p.key); return true; }
        r += code === keys.DOWN ? 1 : -1;
      }
      return true;
    }
    return false;
  }

  signInButton.addEventListener("click", startSignIn);
  details.querySelector("button").addEventListener("click", closeDetails);

  return { show: show, hide: hide, isActive: isActive, handleKey: handleKey, firstFocusable: firstFocusable };
})();
