"""Shared helpers: paths, configuration, password hashing, name normalisation."""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
from pathlib import Path

APP_NAME = "ParentalControl"
DEFAULT_PORT = 8765

# Accounts whose processes are never touched (Windows service accounts).
SYSTEM_ACCOUNTS = {"system", "local service", "network service", ""}

# Process names that must never be blocked or killed: doing so would log the
# user out, crash Windows or break the parental control itself.
PROTECTED_NAMES = {
    "system", "registry", "smss.exe", "csrss.exe", "wininit.exe", "winlogon.exe",
    "services.exe", "lsass.exe", "svchost.exe", "dwm.exe", "fontdrvhost.exe",
    "sihost.exe", "ctfmon.exe", "explorer.exe", "logonui.exe", "userinit.exe",
    "taskhostw.exe", "runtimebroker.exe", "searchhost.exe", "startmenuexperiencehost.exe",
    "shellexperiencehost.exe", "textinputhost.exe", "conhost.exe", "lockapp.exe",
}

# ----------------------------------------------------------- time limits ----
# A limit is a number of minutes of *focused* use per day, per program, per user
# ('' = applies to each user separately). No limit = unlimited.
MAX_LIMIT_MINUTES = 24 * 60
# Pop-up warnings at these numbers of minutes remaining. 0 is the "time is up"
# notice, shown `limit_grace_seconds` before the program is closed and blocked.
LIMIT_WARN_MINUTES = (15, 5, 0)


def parse_limit_minutes(value, allow_none: bool = False) -> int | None:
    """Validate a limit coming from the dashboard. None/'' = unlimited (if allowed)."""
    if value is None or value == "":
        if allow_none:
            return None
        raise ValueError("minutes is required")
    if isinstance(value, bool):
        raise ValueError("minutes must be a whole number")
    try:
        m = int(value)
    except (TypeError, ValueError):
        raise ValueError("minutes must be a whole number") from None
    if m != float(value) or not 0 <= m <= MAX_LIMIT_MINUTES:
        raise ValueError(f"minutes must be a whole number between 0 and {MAX_LIMIT_MINUTES}")
    return m


def data_dir() -> Path:
    """Directory holding the database, config and logs (service side only)."""
    env = os.environ.get("PC_DATA_DIR")
    if env:
        p = Path(env)
    elif os.name == "nt":
        p = Path(os.environ.get("ProgramData", r"C:\ProgramData")) / APP_NAME
    else:
        p = Path("/var/lib/parental-control")
    p.mkdir(parents=True, exist_ok=True)
    return p


def norm_user(name: str | None) -> str:
    """'DESKTOP-1\\Alice' -> 'alice'."""
    if not name:
        return ""
    return name.replace("/", "\\").split("\\")[-1].strip().lower()


def norm_app(name: str | None) -> str:
    return (name or "").strip().lower()


# ---------------------------------------------------------------- config ----

def config_path() -> Path:
    return data_dir() / "config.json"


def load_config() -> dict:
    cfg = {
        "port": DEFAULT_PORT,
        "bind": "0.0.0.0",        # all interfaces: the agents need 127.0.0.1, remote access its own IP
        # Who may open the dashboard besides localhost: [] = anyone the firewall lets in,
        # or CIDR subnets and/or "zerotier" (= this PC's ZeroTier subnets, detected live).
        "allow_from": [],
        "idle_seconds": 300,     # no keyboard/mouse input for this long = idle, not counted
        "report_seconds": 10,     # how often agents report to the service
        "limit_grace_seconds": 30,  # "time is up" pop-up -> program closed and blocked
        "tls_cert": "",
        "tls_key": "",
        "password": None,
    }
    p = config_path()
    if p.exists():
        cfg.update(json.loads(p.read_text(encoding="utf-8-sig")))  # PowerShell 5 writes a BOM
    return cfg


def save_config(cfg: dict) -> None:
    p = config_path()
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    os.replace(tmp, p)


# -------------------------------------------------------------- passwords ----

_ITERATIONS = 300_000


def hash_password(password: str) -> dict:
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, _ITERATIONS)
    return {"salt": salt.hex(), "hash": digest.hex(), "iterations": _ITERATIONS}


def check_password(password: str, stored: dict | None) -> bool:
    if not stored:
        return False
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode(), bytes.fromhex(stored["salt"]), int(stored["iterations"])
    )
    return hmac.compare_digest(digest.hex(), stored["hash"])
