"""Per-user agent, started at every logon (HKLM Run key, launched with pythonw.exe
so there is no window).

Every second it looks at which application owns the focused window and adds
that second to the app's counter, unless the user is idle or the session is
locked. Counters are sent to the local service every few seconds. Messages
from the service (e.g. "Game.exe is blocked") are shown in a message box.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime

import psutil

IS_WIN = os.name == "nt"

if IS_WIN:
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

    user32.GetForegroundWindow.restype = wintypes.HWND
    user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    user32.GetWindowThreadProcessId.restype = wintypes.DWORD
    user32.MessageBoxW.argtypes = [wintypes.HWND, wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.UINT]
    kernel32.GetTickCount.restype = wintypes.DWORD
    kernel32.CreateMutexW.argtypes = [wintypes.LPVOID, wintypes.BOOL, wintypes.LPCWSTR]
    kernel32.CreateMutexW.restype = wintypes.HANDLE
    _EnumProc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    user32.EnumChildWindows.argtypes = [wintypes.HWND, _EnumProc, wintypes.LPARAM]

    class LASTINPUTINFO(ctypes.Structure):
        _fields_ = [("cbSize", wintypes.UINT), ("dwTime", wintypes.DWORD)]

    def idle_seconds() -> float:
        lii = LASTINPUTINFO()
        lii.cbSize = ctypes.sizeof(lii)
        if not user32.GetLastInputInfo(ctypes.byref(lii)):
            return 0.0
        return ((kernel32.GetTickCount() - lii.dwTime) & 0xFFFFFFFF) / 1000.0

    def _pid_of(hwnd) -> int:
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        return pid.value

    def foreground_pid() -> int | None:
        hwnd = user32.GetForegroundWindow()
        if not hwnd:
            return None  # locked, switched user, or desktop transitioning
        pid = _pid_of(hwnd)
        # Store / UWP apps are hosted by ApplicationFrameHost.exe: find the real app.
        try:
            if psutil.Process(pid).name().lower() == "applicationframehost.exe":
                found = []

                def cb(child, _):
                    cpid = _pid_of(child)
                    if cpid and cpid != pid:
                        found.append(cpid)
                        return False
                    return True

                user32.EnumChildWindows(hwnd, _EnumProc(cb), 0)
                if found:
                    return found[0]
        except psutil.Error:
            pass
        return pid or None

    def single_instance() -> bool:
        kernel32.CreateMutexW(None, False, "Local\\ParentalControlAgent")
        return ctypes.get_last_error() != 183  # ERROR_ALREADY_EXISTS

    def show_message(text: str) -> None:
        # MB_ICONINFORMATION | MB_TOPMOST | MB_SETFOREGROUND
        threading.Thread(target=user32.MessageBoxW, args=(None, text, "Parental control", 0x40 | 0x40000 | 0x10000),
                         daemon=True).start()

    def current_user() -> str:
        return os.environ.get("USERNAME", "")
else:  # non-Windows: lets the agent run for development/testing
    def idle_seconds() -> float:
        return 0.0

    def foreground_pid() -> int | None:
        return os.getpid()

    def single_instance() -> bool:
        return True

    def show_message(text: str) -> None:
        print("[message]", text)

    def current_user() -> str:
        return os.environ.get("USER", "")


IGNORED_APPS = {"lockapp.exe", "logonui.exe"}  # lock screen is not "usage"


class Agent:
    def __init__(self, port: int):
        self.url = f"http://127.0.0.1:{port}/agent/report"
        self.idle_limit = 300
        self.report_every = 10
        self.pending: dict[tuple[str, int, str], float] = {}
        self.names: dict[int, tuple[float, str]] = {}  # pid -> (create_time, name) cache
        self.current_app = ""
        self.idle = False
        self.locked = False

    def app_name(self, pid: int) -> str | None:
        try:
            p = psutil.Process(pid)
            ct = p.create_time()
            cached = self.names.get(pid)
            if cached and cached[0] == ct:
                return cached[1]
            name = p.name().lower()
            if len(self.names) > 2000:
                self.names.clear()
            self.names[pid] = (ct, name)
            return name
        except psutil.Error:
            return None

    def tick(self, elapsed: float) -> None:
        pid = foreground_pid()
        app = self.app_name(pid) if pid else None
        self.locked = app is None or app in IGNORED_APPS
        self.idle = idle_seconds() >= self.idle_limit
        self.current_app = "" if self.locked else app
        if self.locked or self.idle:
            return
        now = datetime.now()
        key = (now.strftime("%Y-%m-%d"), now.hour, app)
        self.pending[key] = self.pending.get(key, 0.0) + elapsed

    def report(self) -> None:
        rows = [{"day": d, "hour": h, "app": a, "seconds": round(s, 2)} for (d, h, a), s in self.pending.items()]
        body = json.dumps({
            "user": current_user(), "usage": rows,
            "current_app": self.current_app, "idle": self.idle, "locked": self.locked,
        }).encode()
        req = urllib.request.Request(self.url, data=body, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                resp = json.loads(r.read())
        except (urllib.error.URLError, OSError, ValueError):
            # Service not reachable (e.g. still starting): keep the data and retry later.
            if len(self.pending) > 20000:
                self.pending.clear()
            return
        for k in [(r["day"], r["hour"], r["app"]) for r in rows]:
            self.pending.pop(k, None)
        self.idle_limit = int(resp.get("idle_seconds", self.idle_limit))
        self.report_every = max(2, int(resp.get("report_seconds", self.report_every)))
        for msg in resp.get("notices", []):
            show_message(msg)

    def run(self) -> None:
        last = time.monotonic()
        last_report = 0.0
        while True:
            time.sleep(1)
            now = time.monotonic()
            # Cap so that sleep/hibernate gaps are never counted as usage.
            elapsed = min(now - last, 2.0)
            last = now
            try:
                self.tick(elapsed)
            except Exception:
                pass
            if now - last_report >= self.report_every:
                last_report = now
                self.report()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    args = ap.parse_args()
    if not single_instance():
        sys.exit(0)
    Agent(args.port).run()


if __name__ == "__main__":
    main()
