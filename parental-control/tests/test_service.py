"""End-to-end tests for the service (run on any OS): python -m unittest discover tests"""
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from datetime import date
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
        self.assertEqual(stats["total"], 210)
        self.assertEqual(stats["by_app"][0], {"key": "minecraft.exe", "seconds": 150})
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


if __name__ == "__main__":
    unittest.main()
