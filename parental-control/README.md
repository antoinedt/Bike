# Parental Control (Windows)

A small, self-hosted parental control for a Windows PC where you are the administrator.

- **Tracks time per focused application** for every user who logs in. Time is not
  counted when the user is idle (no input for 5 min) or the screen is locked.
- **Stats dashboard**: who is on the PC right now and in which app, time per day,
  per app, per hour of the day, per user.
- **Remote control** from your phone or another computer: list running programs,
  **stop** one, or **block** a program (for one child or for everyone). A blocked program
  is closed within about 2 seconds each time it starts, and the child sees a message.
- **Activity log**: stops, blocks, failed dashboard logins, and a warning when a
  logged-in user's tracking agent stops reporting (for example, if someone kills it).

## How it works

```
 ┌──────────── Windows PC ──────────────────────────────────────────┐
 │                                                                  │
 │  child's session          SYSTEM (boot, auto-restart)            │
 │  ┌──────────────┐  local  ┌───────────────────────────────┐      │
 │  │  agent.py    │ ──────► │ service.py                    │      │   phone / laptop
 │  │ focused app, │  HTTP   │ • SQLite usage DB             │ ◄────┼── browser
 │  │ idle, lock   │ ◄────── │ • kills blocked processes     │ :8765│   (password)
 │  └──────────────┘ notices │ • web dashboard + JSON API    │      │
 │                           └───────────────────────────────┘      │
 └──────────────────────────────────────────────────────────────────┘
```

- **`service.py`** runs as **SYSTEM**, started by a scheduled task at boot. It is
  restarted every minute if it stops. Standard users can't stop it. It owns the database in
  `C:\ProgramData\ParentalControl`, which only SYSTEM and Administrators can read.
- **`agent.py`** is started for **every user at logon** (HKLM `Run` key, with `pythonw.exe`,
  so no window). Windows doesn't let a SYSTEM service see a user's desktop, so the agent
  reads the focused window itself. It sends its counters to the service on `127.0.0.1`.
  The service works out who the user is from the Windows account that owns the
  connection, so a child can't report time under another name.

## Requirements

- Windows 10/11.
- **Python 3.9+ installed "for all users"**: on python.org, choose *Customize
  installation* and tick *Install Python for all users*. The children's accounts need
  to be able to run it.
- The children's accounts must be **Standard users**, not Administrators. Otherwise
  they could simply stop the service.

## Install

In an **Administrator** PowerShell, from this folder:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\install.ps1
```

The script asks you for a dashboard password, then prints the dashboard addresses
(`http://<pc-ip>:8765`). Users who are already logged in are tracked after their next
sign-in.

Options: `-Port 9000` to use another port.

Uninstall: `scripts\uninstall.ps1` (add `-RemoveData` to also delete the usage history).

Change the password: `python "C:\Program Files\ParentalControl\service.py" set-password`
from an Administrator prompt, then restart the `ParentalControlService` scheduled task
(or reboot).

## Accessing it remotely

The firewall rule opens the port on **Private** networks only, so it works from your home
Wi-Fi. To reach it from outside home, **do not** forward the port on your router.
Instead, install [Tailscale](https://tailscale.com) (free) on the PC and on your phone,
then open `http://<pc-tailscale-name>:8765`. Tailscale encrypts the traffic and only your
devices can reach it.

HTTPS is supported as an option: put `tls_cert` and `tls_key` (paths to PEM files) in
`C:\ProgramData\ParentalControl\config.json`.

## Configuration

`C:\ProgramData\ParentalControl\config.json`:

| key              | default   | meaning                                               |
|------------------|-----------|-------------------------------------------------------|
| `port`           | `8765`    | dashboard port (re-run install with `-Port` to change) |
| `bind`           | `0.0.0.0` | listen address (`127.0.0.1` = local only)             |
| `idle_seconds`   | `300`     | no input for this long: time is not counted           |
| `report_seconds` | `10`      | how often agents send data                            |
| `tls_cert/key`   | empty     | enable HTTPS                                          |

Logs: `C:\ProgramData\ParentalControl\service.log`.

## Limits worth knowing

- Apps are identified by **executable name** (`chrome.exe`, `robloxplayerbeta.exe`).
  Websites inside a browser are not tracked separately. A child could rename an `.exe`
  to get around a block. For stronger blocking, also use Windows *AppLocker* or
  *Microsoft Family Safety*.
- Watching a video without touching the mouse or keyboard for 5 minutes counts as idle.
  Raise `idle_seconds` if you want that time counted.
- A child can kill their own agent from Task Manager. The service then logs
  "Tracking stopped" in the Activity tab, but the agent is only started again at their
  next logon. Blocking still works without the agent because the service enforces it.

## Development

The service and its tests run on any OS:

```bash
pip install psutil
python -m unittest discover -s tests -v
PC_DATA_DIR=/tmp/pc python src/service.py set-password && PC_DATA_DIR=/tmp/pc python src/service.py
```
