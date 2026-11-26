# VibeTV

> **Unofficial, personal use only.** These are my own patches to an Android TV-streaming app for
> my own device and account. This is not an official build, and it is not affiliated with or
> endorsed by the app's publisher. Don't redistribute it.

The patches stop the app from logging you out and wiping settings, and add an error log file.
They target app version 3.1.37 (version code 400142).

The original app is in `original/` and every change is in `patches/patch.py`.
`build.sh` rebuilds the installable APK from those two.

## Build

```sh
./build.sh
```

Needs Java 17+, python3, curl, unzip and zip. Pinned tool versions are downloaded into `tools/`.
Output:

- `dist/VibeTV.apk`: a single APK you can tap to install.
- `dist/VibeTV.apks`: the same app as split APKs, installed with SAI (Split APKs Installer).
  This one keeps the original file layout exactly, so try it if the single APK misbehaves.

### Signing key

Android only lets an update install over an existing app if it is signed with the **same key**.
The key is **not** in this repo, because it is a secret. Put it at `keystore/vibetv.jks`
(that folder is git-ignored) or point to it:

```sh
KEYSTORE=/path/vibetv.jks KS_ALIAS=vibetv KS_PASS=... ./build.sh
```

If no key is found, the build creates a new one. Then you must uninstall the current app
before installing, and log in again.

## Install

1. Uninstall the Play Store version first (different signature).
2. Install `VibeTV.apk`, or the `.apks` with SAI. To install the split APKs over adb:
   `unzip dist/VibeTV.apks -d apks && adb install-multiple apks/*.apk`
3. Turn off Play Store auto-update for the app, or it will replace the patched one.

## Error log

- Main log: `/sdcard/Android/data/<app package>/files/logs/vibetv_errors.log`.
  Rotates to `vibetv_errors.old.log` at 2 MB.
- Copy you can open on the phone: **`Download/VibeTV/vibetv_errors.log`**. It is refreshed when
  the app starts, when it crashes, and at most every 30 s while errors happen.
- Also in logcat under the tag `VibeTV`.

Log entries:

| Tag | Meaning |
|-----|---------|
| `START` | App launch, with device and Android version |
| `CRASH` | Uncaught exception, with full stack trace |
| `ERROR` | Every error passed to the app's central error handler, with stack trace |
| `HTTP` | Every failed API reply: status code and body (first 2000 characters) |
| `EVENT` | Session events: server said session invalid, re-login kept the saved login, forced sign-out (and which kind), logout running |

## What was wrong and what the patches do

Login is OAuth in the browser (Okta, with the `offline_access` scope). The app keeps a refresh
token in `MainPreferences` and a session id in `crprefs`.

1. **A temporary error deleted the saved login.**
   `ReLoginHandleHandler` and `UseCaseErrorHandlerKt` (`handleReLogin`, `runSafety`) try a silent
   re-login when the server reports the session invalid. If that attempt hit
   `UnknownNetworkException` or HTTP 500, they set the refresh token and the session id to
   `null`, which forced a full login. The patch skips that wipe and logs the error instead.
   A refresh token that is really invalid (`INVALID_REFRESH_TOKEN`) still signs you out.
2. **Error bodies were read twice.** `ApiErrorHandlerKt.getBodyOrThrow` called
   `ResponseBody.string()` in `sessionIsNotValid()` and then again for the exception message.
   OkHttp bodies can only be read once, so the real server error was lost. The patch reads it
   once, logs it, and runs the same session-invalid check (`VibeLog.sessionInvalid`: HTTP 500
   and the body contains "is not valid anymore" or "ip address has changed").
3. **Log-out wiped your settings.** `LogOutUseCaseImpl` runs for manual log-out and for forced
   sign-out alike. It cleared the genre filter, player settings (subtitle and audio language,
   subtitles on/off, autoplay) and parental-control settings. The patch removes those three
   calls. Channel and home caches are still cleared, and favourites are stored server-side.
4. **The encrypted credential store deleted itself.** `SecuredUserCredentialsPersistence` used
   `EncryptedSharedPreferences`, which deletes the file and its key when Keystore throws
   `AEADBadTagException` or `KeyStoreException`. That happens after a backup restore or an OS
   update. The patch uses plain private `SharedPreferences` (`crprefs_plain`) instead.
   This is less protected on rooted devices.
5. **Error log.** `ca.vibetv.VibeLog` (`helper/src`) is called from `App.onCreate`, from
   `AppErrorHandlerImpl.printStackTrace`, and from the points listed above.

Not changed:

- **Resources and manifest are untouched** (`apktool d -r`). The first build rebuilt resources
  and crashed after login. Keeping the originals fixed that, so resource rebuilding was most
  likely the cause. For that reason `allowBackup="true"` stays.

## Notes for further work

- `patches/patch.py` asserts each change matched exactly. On a new app version it stops with an
  error instead of patching the wrong code. Fix the patterns, then rerun.
- To read the code: `jadx -d src <base apk>` (jadx 1.5.x). Classes of interest:
  - `ca.vmedia.domain.login.LoginFlowImpl` and `LoginInternalKt`
  - `ca.vmedia.common.OktaHandler`
  - `ca.vmedia.tv.models.NetworkManagerImpl`
  - `ca.vmedia.persistence.*`
- A manual username/password login exists (`AppInfoImpl.isManualLoginEnabled()` returns `false`).
  It posts to the platform's own `api/client/login` instead of the Okta login, so it will most
  likely reject real accounts.
- Idea not built yet: an in-app WebView for the login page that saves and fills in your
  credentials.
- To edit the logger: change `helper/src/ca/vibetv/VibeLog.java`. If it uses a new Android API,
  add a matching stub under `helper/stubs/` (stubs are only used to compile, not packaged).
