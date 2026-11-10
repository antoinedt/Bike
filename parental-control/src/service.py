"""Parental control service.

Runs as SYSTEM (scheduled task at boot). It:
  * receives focused-app usage from the per-user agents (localhost only),
  * stores it in SQLite,
  * kills blocked processes every couple of seconds, in every user session,
  * serves a password-protected web dashboard + JSON API for remote control.

Usage:
    python service.py              # run the service
    python service.py set-password # set / change the dashboard password
"""
from __future__ import annotations

import getpass
import json
import logging
import logging.handlers
import os
import re
import secrets
import sqlite3
import ssl
import sys
import threading
import time
from datetime import date, datetime, timedelta
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import psutil

from common import (
    PROTECTED_NAMES, SYSTEM_ACCOUNTS, check_password, data_dir, hash_password,
    load_config, norm_app, norm_user, save_config,
)

HERE = Path(__file__).resolve().parent
log = logging.getLogger("parental-control")

SCHEMA = """
CREATE TABLE IF NOT EXISTS usage (
    day     TEXT    NOT NULL,          -- YYYY-MM-DD (local time of the PC)
    hour    INTEGER NOT NULL,          -- 0..23
    user    TEXT    NOT NULL,
    app     TEXT    NOT NULL,          -- executable name, lower case
    seconds REAL    NOT NULL,
    PRIMARY KEY (day, hour, user, app)
);
CREATE TABLE IF NOT EXISTS blocklist (
    name    TEXT NOT NULL,             -- executable name, lower case
    user    TEXT NOT NULL DEFAULT '',  -- '' = every user
    created TEXT NOT NULL,
    PRIMARY KEY (name, user)
);
CREATE TABLE IF NOT EXISTS events (
    id     INTEGER PRIMARY KEY AUTOINCREMENT,
    ts     TEXT NOT NULL,
    kind   TEXT NOT NULL,
    user   TEXT NOT NULL DEFAULT '',
    detail TEXT NOT NULL DEFAULT ''
);
"""

DAY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def now_iso() -> str:
    return datetime.now().isoformat(timespec="seconds")


# ================================================================== store ====

class Store:
    def __init__(self, path: Path):
        self.conn = sqlite3.connect(str(path), check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.lock = threading.Lock()
        with self.lock:
            self.conn.executescript(SCHEMA)
            self.conn.commit()

    def _q(self, sql: str, args=()) -> list[sqlite3.Row]:
        with self.lock:
            return self.conn.execute(sql, args).fetchall()

    def _x(self, sql: str, args=()) -> None:
        with self.lock:
            self.conn.execute(sql, args)
            self.conn.commit()

    # -- usage
    def add_usage(self, user: str, rows: list[dict]) -> None:
        clean = []
        for r in rows:
            day, hour, app, sec = str(r.get("day", "")), r.get("hour"), norm_app(r.get("app")), r.get("seconds")
            if not DAY_RE.match(day) or not isinstance(hour, int) or not 0 <= hour <= 23:
                continue
            if not app or not isinstance(sec, (int, float)) or not 0 < sec <= 4000:
                continue
            clean.append((day, hour, user, app[:200], min(float(sec), 3600.0)))
        if not clean:
            return
        with self.lock:
            self.conn.executemany(
                "INSERT INTO usage(day,hour,user,app,seconds) VALUES(?,?,?,?,?) "
                "ON CONFLICT(day,hour,user,app) DO UPDATE SET seconds = seconds + excluded.seconds",
                clean,
            )
            self.conn.commit()

    def stats(self, start: str, end: str, user: str = "") -> dict:
        where, args = "day BETWEEN ? AND ?", [start, end]
        if user:
            where += " AND user = ?"
            args.append(user)

        def grp(col: str, order: str):
            return [
                {"key": r[0], "seconds": round(r[1])}
                for r in self._q(f"SELECT {col}, SUM(seconds) FROM usage WHERE {where} GROUP BY {col} ORDER BY {order}", args)
            ]

        total = self._q(f"SELECT COALESCE(SUM(seconds),0) FROM usage WHERE {where}", args)[0][0]
        return {
            "from": start, "to": end, "user": user, "total": round(total),
            "by_app": grp("app", "2 DESC"),
            "by_day": grp("day", "1"),
            "by_hour": grp("hour", "1"),
            "by_user": grp("user", "2 DESC"),
        }

    def users(self) -> list[str]:
        return [r[0] for r in self._q("SELECT DISTINCT user FROM usage ORDER BY user")]

    # -- blocklist
    def block_rules(self) -> list[dict]:
        return [dict(r) for r in self._q("SELECT name, user, created FROM blocklist ORDER BY name, user")]

    def add_block(self, name: str, user: str) -> None:
        self._x("INSERT OR IGNORE INTO blocklist(name,user,created) VALUES(?,?,?)", (name, user, now_iso()))

    def remove_block(self, name: str, user: str) -> None:
        self._x("DELETE FROM blocklist WHERE name=? AND user=?", (name, user))

    # -- events
    def event(self, kind: str, user: str = "", detail: str = "") -> None:
        log.info("event %s user=%s %s", kind, user, detail)
        self._x("INSERT INTO events(ts,kind,user,detail) VALUES(?,?,?,?)", (now_iso(), kind, user, detail))
        self._x("DELETE FROM events WHERE id <= (SELECT MAX(id) FROM events) - 5000")

    def events(self, limit: int = 200) -> list[dict]:
        return [dict(r) for r in self._q("SELECT ts, kind, user, detail FROM events ORDER BY id DESC LIMIT ?", (limit,))]


# ================================================================ service ====

class Service:
    def __init__(self, cfg: dict, store: Store):
        self.cfg = cfg
        self.store = store
        self.port = int(cfg["port"])
        self.sessions: dict[str, float] = {}          # token -> expiry
        self.live: dict[str, dict] = {}                # user -> last agent heartbeat
        self.notices: dict[str, list[str]] = {}        # user -> messages for the agent to display
        self.missing_reported: set[str] = set()
        self.kill_log_throttle: dict[tuple, float] = {}
        self.failed_logins: list[float] = []
        self.started = time.time()
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.own_dir = str(HERE).lower()

    # ------------------------------------------------------------ helpers --
    def is_protected(self, proc: psutil.Process, name: str) -> bool:
        if proc.pid == os.getpid() or name in PROTECTED_NAMES:
            return True
        if name in ("python.exe", "pythonw.exe", "python", "python3"):
            try:
                return any(self.own_dir in a.lower() for a in proc.cmdline())
            except psutil.Error:
                return False
        return False

    def notify(self, user: str, msg: str) -> None:
        with self.lock:
            lst = self.notices.setdefault(user, [])
            if msg not in lst:
                lst.append(msg)

    def user_processes(self) -> list[dict]:
        out = []
        for p in psutil.process_iter(["pid", "name", "username", "memory_info", "create_time"]):
            user = norm_user(p.info["username"])
            name = norm_app(p.info["name"])
            if user in SYSTEM_ACCOUNTS or name in PROTECTED_NAMES or self.is_protected(p, name):
                continue
            mem = p.info["memory_info"]
            out.append({
                "pid": p.info["pid"], "name": p.info["name"], "user": user,
                "memory_mb": round(mem.rss / 1048576, 1) if mem else None,
                "started": datetime.fromtimestamp(p.info["create_time"]).isoformat(timespec="seconds")
                if p.info["create_time"] else None,
            })
        out.sort(key=lambda x: (x["user"], x["name"].lower()))
        return out

    def kill_pid(self, pid: int, reason: str) -> tuple[bool, str]:
        try:
            p = psutil.Process(pid)
            name, user = norm_app(p.name()), norm_user(p.username())
        except psutil.Error as e:
            return False, f"process not found: {e}"
        if user in SYSTEM_ACCOUNTS or self.is_protected(p, name):
            return False, f"{name} is protected and cannot be stopped"
        try:
            p.kill()
        except psutil.Error as e:
            return False, str(e)
        self.store.event("killed", user, f"{name} (pid {pid}) - {reason}")
        self.notify(user, f"{p.name()} was closed by your parent.")
        return True, "ok"

    # -------------------------------------------------------- enforcement --
    def enforce_once(self) -> int:
        rules = self.store.block_rules()
        if not rules:
            return 0
        by_name: dict[str, set[str]] = {}
        for r in rules:
            by_name.setdefault(r["name"], set()).add(r["user"])
        killed = 0
        for p in psutil.process_iter(["pid", "name", "username"]):
            name = norm_app(p.info["name"])
            users = by_name.get(name)
            if not users:
                continue
            user = norm_user(p.info["username"])
            if user in SYSTEM_ACCOUNTS or not ("" in users or user in users) or self.is_protected(p, name):
                continue
            try:
                p.kill()
            except psutil.Error:
                continue
            killed += 1
            key = (name, user)
            if time.time() - self.kill_log_throttle.get(key, 0) > 60:
                self.kill_log_throttle[key] = time.time()
                self.store.event("blocked", user, f"{p.info['name']} was started and closed (blocked)")
                self.notify(user, f"{p.info['name']} is blocked by parental control.")
        return killed

    def check_agents(self) -> None:
        """Log when a logged-in user has no agent reporting (agent killed / not started)."""
        if time.time() - self.started < 180:
            return
        logged_in = {norm_user(u.name) for u in psutil.users()} - SYSTEM_ACCOUNTS
        for user in logged_in:
            last = self.live.get(user, {}).get("ts", 0)
            if time.time() - last > 120:
                if user not in self.missing_reported:
                    self.missing_reported.add(user)
                    self.store.event("agent_missing", user, "user is logged in but the tracking agent is not reporting")
            elif user in self.missing_reported:
                self.missing_reported.discard(user)
                self.store.event("agent_back", user, "tracking agent is reporting again")

    def background_loop(self) -> None:
        last_check = 0.0
        while not self.stop.is_set():
            try:
                self.enforce_once()
                if time.time() - last_check > 60:
                    last_check = time.time()
                    self.check_agents()
            except Exception:
                log.exception("background loop error")
            self.stop.wait(2)

    # --------------------------------------------------------------- auth --
    def login(self, password: str) -> str | None:
        now = time.time()
        self.failed_logins = [t for t in self.failed_logins if now - t < 300]
        if len(self.failed_logins) >= 10:
            return None
        if not check_password(password, self.cfg.get("password")):
            self.failed_logins.append(now)
            self.store.event("login_failed", "", "")
            return None
        token = secrets.token_urlsafe(32)
        with self.lock:
            self.sessions = {t: e for t, e in self.sessions.items() if e > now}
            self.sessions[token] = now + 7 * 86400
        return token

    def valid_token(self, token: str | None) -> bool:
        return bool(token) and self.sessions.get(token, 0) > time.time()

    # ----------------------------------------------------------- agent IO --
    def peer_user(self, client_port: int) -> str | None:
        """Find which Windows user owns the socket the agent connected from,
        so a child cannot report usage under another name."""
        try:
            for c in psutil.net_connections(kind="tcp"):
                if c.laddr and c.raddr and c.pid and c.laddr.port == client_port and c.raddr.port == self.port:
                    return norm_user(psutil.Process(c.pid).username())
        except psutil.Error:
            pass
        return None

    def agent_report(self, body: dict, client_port: int) -> dict:
        user = self.peer_user(client_port) or norm_user(body.get("user"))
        if not user:
            raise ValueError("unknown user")
        rows = body.get("usage") or []
        if isinstance(rows, list):
            self.store.add_usage(user, rows[:5000])
        with self.lock:
            self.live[user] = {
                "ts": time.time(),
                "app": norm_app(body.get("current_app")),
                "idle": bool(body.get("idle")),
                "locked": bool(body.get("locked")),
            }
            notices = self.notices.pop(user, [])
        blocked = sorted({r["name"] for r in self.store.block_rules() if r["user"] in ("", user)})
        return {
            "user": user,
            "blocked": blocked,
            "notices": notices,
            "idle_seconds": int(self.cfg["idle_seconds"]),
            "report_seconds": int(self.cfg["report_seconds"]),
        }

    def live_view(self) -> list[dict]:
        out = []
        today = date.today().isoformat()
        totals = {r["key"]: r["seconds"] for r in self.store.stats(today, today)["by_user"]}
        with self.lock:
            live = dict(self.live)
        for user in sorted(set(live) | set(totals)):
            info = live.get(user, {})
            online = time.time() - info.get("ts", 0) < max(30, 3 * int(self.cfg["report_seconds"]))
            out.append({
                "user": user, "online": online,
                "app": info.get("app") if online else None,
                "idle": info.get("idle", False) if online else None,
                "locked": info.get("locked", False) if online else None,
                "last_seen": datetime.fromtimestamp(info["ts"]).isoformat(timespec="seconds") if info else None,
                "today_seconds": totals.get(user, 0),
            })
        return out


# ================================================================== http ====

class Handler(BaseHTTPRequestHandler):
    server_version = "ParentalControl/1.0"
    svc: Service  # set on the class before serving

    def log_message(self, fmt, *args):  # route to our logger
        log.debug("%s - %s", self.client_address[0], fmt % args)

    # -- helpers
    def send_json(self, obj, status=200, headers=None):
        data = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def read_json(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if n > 2_000_000:
            raise ValueError("body too large")
        if n and "application/json" not in (self.headers.get("Content-Type") or ""):
            raise ValueError("expected application/json")
        body = json.loads(self.rfile.read(n) or b"{}")
        if not isinstance(body, dict):
            raise ValueError("expected an object")
        return body

    def token(self) -> str | None:
        c = SimpleCookie(self.headers.get("Cookie") or "")
        return c["pc_session"].value if "pc_session" in c else None

    def authed(self) -> bool:
        if self.svc.valid_token(self.token()):
            return True
        self.send_json({"error": "login required"}, 401)
        return False

    def is_local(self) -> bool:
        return self.client_address[0] in ("127.0.0.1", "::1", "::ffff:127.0.0.1")

    # -- GET
    def do_GET(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        svc = self.svc
        try:
            if url.path in ("/", "/index.html"):
                data = (HERE / "dashboard.html").read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.send_header("X-Frame-Options", "DENY")
                self.end_headers()
                self.wfile.write(data)
                return
            if url.path == "/api/session":
                return self.send_json({"logged_in": svc.valid_token(self.token())})
            if not url.path.startswith("/api/"):
                return self.send_json({"error": "not found"}, 404)
            if not self.authed():
                return
            if url.path == "/api/live":
                return self.send_json(svc.live_view())
            if url.path == "/api/users":
                return self.send_json(sorted(set(svc.store.users()) | set(svc.live)))
            if url.path == "/api/stats":
                today = date.today()
                start = q.get("from") or today.isoformat()
                end = q.get("to") or today.isoformat()
                if not (DAY_RE.match(start) and DAY_RE.match(end)):
                    return self.send_json({"error": "bad date"}, 400)
                return self.send_json(svc.store.stats(start, end, norm_user(q.get("user"))))
            if url.path == "/api/processes":
                return self.send_json(svc.user_processes())
            if url.path == "/api/blocklist":
                return self.send_json(svc.store.block_rules())
            if url.path == "/api/events":
                return self.send_json(svc.store.events(int(q.get("limit", 200))))
            return self.send_json({"error": "not found"}, 404)
        except Exception as e:
            log.exception("GET %s", self.path)
            self.send_json({"error": str(e)}, 500)

    # -- POST
    def do_POST(self):
        url = urlparse(self.path)
        svc = self.svc
        try:
            body = self.read_json()
        except (ValueError, json.JSONDecodeError) as e:
            return self.send_json({"error": str(e)}, 400)
        try:
            if url.path == "/agent/report":
                if not self.is_local():
                    return self.send_json({"error": "forbidden"}, 403)
                return self.send_json(svc.agent_report(body, self.client_address[1]))

            if url.path == "/api/login":
                token = svc.login(str(body.get("password", "")))
                if not token:
                    time.sleep(1)
                    return self.send_json({"error": "wrong password"}, 401)
                secure = "; Secure" if svc.cfg.get("tls_cert") else ""
                return self.send_json({"ok": True}, headers={
                    "Set-Cookie": f"pc_session={token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=604800{secure}"})
            if url.path == "/api/logout":
                svc.sessions.pop(self.token() or "", None)
                return self.send_json({"ok": True}, headers={"Set-Cookie": "pc_session=; Path=/; Max-Age=0"})

            if not self.authed():
                return
            if url.path == "/api/kill":
                ok, msg = svc.kill_pid(int(body["pid"]), "stopped from dashboard")
                return self.send_json({"ok": ok, "message": msg}, 200 if ok else 400)
            if url.path == "/api/block":
                name, user = norm_app(body.get("name")), norm_user(body.get("user"))
                if not name or "\\" in name or "/" in name:
                    return self.send_json({"error": "give an executable name, e.g. robloxplayerbeta.exe"}, 400)
                if name in PROTECTED_NAMES:
                    return self.send_json({"error": f"{name} is a Windows component and cannot be blocked"}, 400)
                svc.store.add_block(name, user)
                svc.store.event("block_added", user, name)
                killed = svc.enforce_once() if body.get("kill_now", True) else 0
                return self.send_json({"ok": True, "killed": killed})
            if url.path == "/api/unblock":
                name, user = norm_app(body.get("name")), norm_user(body.get("user"))
                svc.store.remove_block(name, user)
                svc.store.event("block_removed", user, name)
                return self.send_json({"ok": True})
            return self.send_json({"error": "not found"}, 404)
        except (KeyError, ValueError, TypeError) as e:
            self.send_json({"error": f"bad request: {e}"}, 400)
        except Exception as e:
            log.exception("POST %s", self.path)
            self.send_json({"error": str(e)}, 500)


def make_server(svc: Service) -> ThreadingHTTPServer:
    handler = type("BoundHandler", (Handler,), {"svc": svc})
    httpd = ThreadingHTTPServer((svc.cfg["bind"], svc.port), handler)
    httpd.daemon_threads = True
    if svc.cfg.get("tls_cert") and svc.cfg.get("tls_key"):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(svc.cfg["tls_cert"], svc.cfg["tls_key"])
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    return httpd


# ==================================================================== cli ====

def setup_logging() -> None:
    h = logging.handlers.RotatingFileHandler(data_dir() / "service.log", maxBytes=2_000_000, backupCount=3,
                                             encoding="utf-8")
    h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    log.addHandler(h)
    if sys.stderr:
        log.addHandler(logging.StreamHandler())
    log.setLevel(logging.INFO)


def set_password() -> None:
    cfg = load_config()
    while True:
        pw = getpass.getpass("New dashboard password (min 8 chars): ")
        if len(pw) < 8:
            print("Too short.")
            continue
        if pw != getpass.getpass("Repeat: "):
            print("Passwords differ.")
            continue
        break
    cfg["password"] = hash_password(pw)
    save_config(cfg)
    print("Password saved. Restart the service for it to take effect.")


def main() -> None:
    if len(sys.argv) > 1 and sys.argv[1] == "set-password":
        return set_password()
    setup_logging()
    cfg = load_config()
    if not cfg.get("password"):
        log.error("No dashboard password set. Run: python service.py set-password")
        sys.exit(1)
    save_config(cfg)  # writes defaults for any missing keys so they can be edited
    store = Store(data_dir() / "usage.db")
    svc = Service(cfg, store)
    store.event("service_started", "", f"port {svc.port}")
    threading.Thread(target=svc.background_loop, daemon=True).start()
    httpd = make_server(svc)
    log.info("Dashboard on %s://%s:%s", "https" if cfg.get("tls_cert") else "http", cfg["bind"], svc.port)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
