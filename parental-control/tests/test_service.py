"""End-to-end tests for the service (run on any OS): python -m unittest discover tests"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from datetime import date, datetime
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / "src"
sys.path.insert(0, str(SRC))
os.environ["PC_DATA_DIR"] = tempfile.mkdtemp()

from common import hash_password, load_config  # noqa: E402
import service  # noqa: E402


class ServiceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cfg = load_config()
        cfg.update(port=0, bind="127.0.0.1", password=hash_password("secret123"))
        store = service.Store(Path(os.environ["PC_DATA_DIR"]) / "usage.db")
        cls.svc = service.Service(cfg, store)
        cls.httpd = service.make_server(cls.svc)
        cls.svc.port = cls.httpd.server_address[1]
        cls.base = f"http://127.0.0.1:{cls.svc.port}"
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()
        cls.cookie = None

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()

    def call(self, path, body=None, cookie=True):
        req = urllib.request.Request(self.base + path, data=None if body is None else json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
        if cookie and self.cookie:
            req.add_header("Cookie", self.cookie)
        try:
            with urllib.request.urlopen(req) as r:
                if r.headers.get("Set-Cookie"):
                    type(self).cookie = r.headers["Set-Cookie"].split(";")[0]
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def login(self):
        status, _ = self.call("/api/login", {"password": "secret123"}, cookie=False)
        self.assertEqual(status, 200)

    def test_requires_login(self):
        status, _ = self.call("/api/stats", cookie=False)
        self.assertEqual(status, 401)

    def test_dashboard_served(self):
        with urllib.request.urlopen(self.base + "/") as r:
            self.assertIn(b"Parental Control", r.read())

    def test_report_and_stats(self):
        self.login()
        today = date.today().isoformat()
        # Other tests share the database and the user: compare with what was there before.
        before = {}
        for s in self.svc.store.stats(today, today)["by_user"]:
            before[s["key"]] = s["seconds"]
        mc_before = {(r["key"]): r["seconds"] for r in self.svc.store.stats(today, today)["by_app"]}.get("minecraft.exe", 0)
        status, resp = self.call("/agent/report", {
            "user": "spoofed", "current_app": "Minecraft.exe",
            "usage": [{"day": today, "hour": 15, "app": "Minecraft.exe", "seconds": 120},
                      {"day": today, "hour": 16, "app": "chrome.exe", "seconds": 60},
                      {"day": "bad", "hour": 1, "app": "x.exe", "seconds": 5}]}, cookie=False)
        self.assertEqual(status, 200)
        user = resp["user"]
        # The user is taken from the socket owner, not from the request body.
        self.assertNotEqual(user, "spoofed")
        self.call("/agent/report", {"current_app": "minecraft.exe",
                                   "usage": [{"day": today, "hour": 15, "app": "minecraft.exe", "seconds": 30}]},
                  cookie=False)
        status, stats = self.call(f"/api/stats?from={today}&to={today}&user={user}")
        self.assertEqual(status, 200)
        self.assertEqual(stats["total"] - before.get(user, 0), 210)
        mc = next(a["seconds"] for a in stats["by_app"] if a["key"] == "minecraft.exe")
        self.assertEqual(mc - mc_before, 150)
        status, live = self.call("/api/live")
        self.assertTrue(any(u["user"] == user and u["online"] and u["app"] == "minecraft.exe" for u in live))

    def test_block_and_kill(self):
        self.login()
        proc = subprocess.Popen(["sleep", "300"])
        try:
            status, procs = self.call("/api/processes")
            self.assertTrue(any(p["pid"] == proc.pid for p in procs))
            status, resp = self.call("/api/block", {"name": "sleep", "user": ""})
            self.assertEqual(status, 200)
            self.assertGreaterEqual(resp["killed"], 1)
            proc.wait(timeout=5)
            # Starting it again gets killed by the enforcement pass.
            proc2 = subprocess.Popen(["sleep", "300"])
            time.sleep(0.2)
            self.svc.enforce_once()
            try:
                proc2.wait(timeout=5)
            finally:
                proc2.kill()
            status, resp = self.call("/agent/report", {"usage": []}, cookie=False)
            self.assertIn("sleep", resp["blocked"])
            self.assertTrue(resp["notices"])
            self.call("/api/unblock", {"name": "sleep", "user": ""})
            self.assertEqual(self.call("/api/blocklist")[1], [])
        finally:
            proc.kill()

    def test_kill_pid(self):
        self.login()
        proc = subprocess.Popen(["sleep", "300"])
        try:
            status, resp = self.call("/api/kill", {"pid": proc.pid})
            self.assertEqual(status, 200, resp)
            proc.wait(timeout=5)
        finally:
            proc.kill()
        status, resp = self.call("/api/kill", {"pid": os.getpid()})
        self.assertEqual(status, 400)  # service's own process is protected

    def test_protected_names_cannot_be_blocked(self):
        self.login()
        status, _ = self.call("/api/block", {"name": "explorer.exe"})
        self.assertEqual(status, 400)

    # ------------------------------------------------------------ limits --
    def report(self, app=None, seconds=0):
        usage = []
        if app:
            now = datetime.now()
            usage = [{"day": now.strftime("%Y-%m-%d"), "hour": now.hour, "app": app, "seconds": seconds}]
        status, resp = self.call("/agent/report", {"current_app": app or "", "usage": usage}, cookie=False)
        self.assertEqual(status, 200, resp)
        return resp

    def status_for(self, user, app):
        status, d = self.call("/api/limits")
        self.assertEqual(status, 200, d)
        return next((s for s in d["status"] if s["user"] == user and s["app"] == app), None)

    def test_limit_validation(self):
        self.login()
        for body in ({"name": "explorer.exe", "minutes": 10},             # Windows component
                     {"name": "game.exe", "minutes": -1},                 # out of range
                     {"name": "game.exe", "minutes": 2000},
                     {"name": "game.exe", "minutes": 1.5},
                     {"name": "game.exe", "minutes": None},               # unlimited only as a today override
                     {"name": "game.exe", "minutes": 10, "scope": "week"}):
            status, _ = self.call("/api/limit", body)
            self.assertEqual(status, 400, body)

    def test_no_limit_means_unlimited(self):
        self.login()
        user = self.report("free.exe", 4 * 3600)["user"]
        self.assertIsNone(self.status_for(user, "free.exe"))
        self.svc.check_limits()
        self.assertNotIn(("free.exe", user), self.svc.limit_blocked)

    def test_limit_warnings_block_and_overrides(self):
        self.login()
        app = "limitgame.exe"
        user = self.report()["user"]
        grace = int(self.svc.cfg["limit_grace_seconds"])
        try:
            self.assertEqual(self.call("/api/limit", {"name": "LimitGame.exe", "user": user, "minutes": 20})[0], 200)
            self.report()  # drain notices
            # 6 of 20 minutes used: 14 left -> the 15-minute warning, once.
            self.report(app, 6 * 60)
            self.svc.check_limits()
            texts = [n["text"] for n in self.report()["notices"]]
            self.assertIn(f"14 minutes left on {app} today.", texts)
            self.svc.check_limits()
            self.assertEqual(self.report()["notices"], [])
            # 16 used: 4 left -> the 5-minute warning, and the agent is asked to report faster.
            self.report(app, 10 * 60)
            self.svc.check_limits()
            resp = self.report()
            self.assertIn(f"4 minutes left on {app} today.", [n["text"] for n in resp["notices"]])
            self.assertEqual(resp["notices"][0]["level"], "warning")
            # 20 used: time-up notice, then blocked once the grace period is over.
            self.report(app, 4 * 60)
            t0 = time.time()
            self.svc.check_limits(now=t0)
            self.assertTrue(any(n["text"].startswith(f"Time is up for {app}") for n in self.report()["notices"]))
            self.assertEqual(self.status_for(user, app)["state"], "closing")
            self.assertNotIn((app, user), self.svc.limit_blocked)
            self.svc.check_limits(now=t0 + grace + 1)
            self.assertIn((app, user), self.svc.limit_blocked)
            resp = self.report()
            self.assertIn(app, resp["blocked"])
            self.assertEqual(resp["report_seconds"], 10)  # blocked, no longer "near"
            # A today-only override of 30 min unblocks it and wins over the daily limit.
            self.assertEqual(self.call("/api/limit", {"name": app, "user": user, "minutes": 30, "scope": "today"})[0], 200)
            s = self.status_for(user, app)
            self.assertEqual((s["minutes"], s["source"], s["state"], s["remaining_seconds"]), (30, "today", "ok", 600))
            self.assertNotIn((app, user), self.svc.limit_blocked)
            # ...an empty override = unlimited today.
            self.call("/api/limit", {"name": app, "user": user, "minutes": None, "scope": "today"})
            self.assertEqual(self.status_for(user, app)["state"], "unlimited")
            # Removing the override goes back to the daily limit (used up -> blocked again after grace).
            self.call("/api/limit/remove", {"name": app, "user": user, "scope": "today"})
            self.svc.check_limits(now=time.time() + grace + 1)
            self.assertIn((app, user), self.svc.limit_blocked)
        finally:
            self.call("/api/limit/remove", {"name": app, "user": user, "scope": "today"})
            self.call("/api/limit/remove", {"name": app, "user": user})
        self.svc.check_limits()
        self.assertIsNone(self.status_for(user, app))
        self.assertNotIn((app, user), self.svc.limit_blocked)

    def test_user_rule_wins_over_everyone_rule(self):
        defaults = {("g.exe", ""): 60, ("g.exe", "bob"): 30}
        overrides = {("g.exe", ""): 90}
        r = service.Service.resolve_limit
        self.assertEqual(r("g.exe", "bob", defaults, {}), (30, "default"))
        self.assertEqual(r("g.exe", "amy", defaults, {}), (60, "default"))
        self.assertEqual(r("g.exe", "bob", defaults, overrides), (90, "today"))
        self.assertEqual(r("g.exe", "bob", defaults, {("g.exe", "bob"): None}), (None, "today"))
        self.assertEqual(r("x.exe", "bob", defaults, overrides), (None, None))

    def test_zero_minute_limit_kills_process(self):
        """0 minutes = not allowed: the running program is closed by the same
        enforcement pass as the blocklist."""
        self.login()
        tmp = Path(tempfile.mkdtemp())
        if os.name == "nt":
            exe, args = tmp / "pclimittest.exe", ["-n", "300", "127.0.0.1"]
            shutil.copy(Path(os.environ["SystemRoot"]) / "System32" / "PING.EXE", exe)
        else:
            exe, args = tmp / "pclimittest.exe", ["300"]
            shutil.copy(shutil.which("sleep"), exe)
        user = self.report()["user"]
        proc = subprocess.Popen([str(exe), *args], stdout=subprocess.DEVNULL)
        try:
            time.sleep(0.3)
            status, resp = self.call("/api/limit", {"name": "pclimittest.exe", "user": user, "minutes": 0, "scope": "today"})
            self.assertEqual(status, 200, resp)
            self.assertGreaterEqual(resp["killed"], 1)
            proc.wait(timeout=5)
            self.assertIn("blocked until midnight", " ".join(n["text"] for n in self.report()["notices"]))
        finally:
            proc.kill()
            self.call("/api/limit/remove", {"name": "pclimittest.exe", "user": user, "scope": "today"})


if __name__ == "__main__":
    unittest.main()
