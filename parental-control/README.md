# Parental Control (Windows)

A small, self-hosted parental control for a Windows PC where you are the administrator.

- **Tracks time per focused application** for every user who logs in. Time is not
  counted when the user is idle (no input for 5 min) or the screen is locked.
- **Stats dashboard**: who is on the PC right now and in which app, time per day,
  per app, per hour of the day, per user.
- **Remote control** from your phone or another computer: list running programs,
  **stop** one, or **block** a program (for one child or for everyone). A blocked program
  is closed within about 2 seconds each time it starts, and the child sees a message.
- **Daily time limits** per program, for one child or for each child (*Limits* tab).
  Programs are unlimited unless you set a limit. A limit counts minutes of focused use
  per day. You can set a permanent daily limit, or change it **for today only**
  (more or less time, unlimited, or 0 = not allowed today). The child gets a pop-up at
  15 and 5 minutes left. At 0, a "time is up" pop-up appears, and 30 seconds later the
  program is closed and blocked until midnight (the same blocking as above).
- **Activity log**: stops, blocks, limits reached, failed dashboard logins, and a warning when a
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
- **Python 3.9+ installed "for all users"**. Use the python.org **"Windows installer (64-bit)"**,
  not the newer *Python Install Manager*, which installs into your own profile
  (`AppData\Local\Python\...`) where the children's accounts can't run it. In the installer,
  choose *Customize installation*, then tick *Install Python for all users* on the
  *Advanced Options* page. It should end up in `C:\Program Files\Python3xx`. The install script
  finds it automatically. Otherwise pass `-PythonPath "C:\...\python.exe"`.
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

Update to a newer version (keeps settings, password and data, and restarts the service and
the agent of every logged-in user): `powershell -ExecutionPolicy Bypass -File scripts\update.ps1`
from an Administrator PowerShell.

Uninstall: `scripts\uninstall.ps1` (add `-RemoveData` to also delete the usage history).

Change the password: `python "C:\Program Files\ParentalControl\service.py" set-password`
from an Administrator prompt, then restart the `ParentalControlService` scheduled task
(or reboot).

## Accessing it remotely

**Do not** forward the port on your router. Use ZeroTier (below) or another private
VPN such as [Tailscale](https://tailscale.com).

Without ZeroTier on the PC, the firewall rule opens the port on **Private** networks only,
so the dashboard works from your home Wi-Fi.

### From your phone with ZeroTier

ZeroTier puts the PC and your phone on a private, encrypted network that works from
anywhere (home Wi-Fi, mobile data, elsewhere).

1. **On the PC**: install [ZeroTier One](https://www.zerotier.com/download/) and join
   your network (tray icon → *Join New Network…* → your 16-character network ID). In
   [my.zerotier.com](https://my.zerotier.com), tick *Auth* for the PC, like you did for the phone.
2. **Apply it to Parental Control**: from an Administrator PowerShell in this folder, run
   `powershell -ExecutionPolicy Bypass -File scripts\update.ps1` (or `install.ps1` on a
   new install). It detects the ZeroTier adapter, then:
   - limits the firewall rule to your ZeroTier subnet, on the ZeroTier adapter only
     (any Windows network profile: ZeroTier networks often show as "Public").
     **Home Wi-Fi access stops**: use ZeroTier from the phone at home too;
   - sets `allow_from: ["zerotier"]`, so the service itself also refuses anything else.
     It re-reads the ZeroTier subnet every minute. Localhost always works.

   At the end it prints the address to use, e.g. `from your phone (ZeroTier): http://10.147.17.42:8765`.
3. **Find the PC's ZeroTier IP yourself** if needed: in *my.zerotier.com* → your network →
   *Members* → the PC's *Managed IPs*. You can also run `ipconfig` on the PC and look at
   the *ZeroTier One [network id]* adapter, or run
   `& "C:\Program Files (x86)\ZeroTier\One\zerotier-cli.bat" listnetworks` as Administrator.
4. **On the phone**: turn ZeroTier One on (the network toggle), then open
   `http://<pc-zerotier-ip>:8765` in the browser and sign in.

If the PC's ZeroTier subnet changes (different network, new IP range in my.zerotier.com),
run `update.ps1` again: it rewrites the firewall rule only when something changed.
To go back to home-Wi-Fi access, set `allow_from` to `[]` in the config, leave the
ZeroTier network, and run `update.ps1`.

HTTPS is supported as an option: put `tls_cert` and `tls_key` (paths to PEM files) in
`C:\ProgramData\ParentalControl\config.json`.

## Configuration

`C:\ProgramData\ParentalControl\config.json`:

| key              | default   | meaning                                               |
|------------------|-----------|-------------------------------------------------------|
| `port`           | `8765`    | dashboard port (re-run install with `-Port` to change) |
| `bind`           | `0.0.0.0` | listen address (`127.0.0.1` = local only). Keep `0.0.0.0`: the agents use 127.0.0.1 |
| `allow_from`     | `[]`      | who may open the dashboard besides localhost: `[]` = anyone the firewall lets in; CIDR subnets and/or `"zerotier"` (set automatically when ZeroTier is found) |
| `idle_seconds`   | `300`     | no input for this long: time is not counted           |
| `report_seconds` | `10`      | how often agents send data (2 s while a limit is close) |
| `limit_grace_seconds` | `30` | time between the "time is up" pop-up and the program closing |
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
