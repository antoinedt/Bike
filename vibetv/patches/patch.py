"""Apply VibeTV fixes to an app decoded with `apktool d -r`.

Usage: python3 -I patch.py <decoded_dir>
Each step asserts it matched exactly, so a new app version fails loudly instead of silently mis-patching.
"""
import glob
import os
import re
import sys

ROOT = sys.argv[1]
LOG = "Lca/vibetv/VibeLog;"


def find(rel):
    hits = glob.glob(os.path.join(ROOT, "smali*", rel))
    assert len(hits) == 1, (rel, hits)
    return hits[0]


def edit(rel, fn):
    p = find(rel)
    s = open(p, encoding="utf-8").read()
    n = fn(s)
    assert n != s, "no change in " + rel
    open(p, "w", encoding="utf-8").write(n)
    print("patched", rel)


def method(s, sig):
    """Return (start, end) span of a method whose .method line contains sig."""
    i = s.index(".method " + sig) if not sig.startswith(".method") else s.index(sig)
    j = s.index(".end method", i) + len(".end method")
    return i, j


def replace_once(s, old, new):
    assert s.count(old) == 1, ("expected once", old[:80], s.count(old))
    return s.replace(old, new)


# 1. Error log file: init in App.onCreate, log every handled error.
edit("ca/vmedia/tv/App.smali", lambda s: replace_once(
    s,
    "    invoke-super {p0}, Landroid/app/Application;->onCreate()V\n",
    "    invoke-super {p0}, Landroid/app/Application;->onCreate()V\n\n"
    f"    invoke-static {{p0}}, {LOG}->init(Landroid/content/Context;)V\n"))

edit("ca/vmedia/tv/errors/AppErrorHandlerImpl.smali", lambda s: replace_once(
    s,
    "    .line 100\n    iget-object v0, p0, Lca/vmedia/tv/errors/AppErrorHandlerImpl;->appInfo:",
    f"    invoke-static {{p1}}, {LOG}->e(Ljava/lang/Throwable;)V\n\n"
    "    .line 100\n    iget-object v0, p0, Lca/vmedia/tv/errors/AppErrorHandlerImpl;->appInfo:"))


# 2. API error body was read twice (2nd read throws "closed"), hiding the real
#    server error. Read once, log it, then decide.
def fix_body(s):
    a, b = method(s, "public static final getBodyOrThrow(")
    m = s[a:b]
    old = m[m.index("    .line 15\n"):m.index("    .line 17\n")]
    new = (
        "    .line 15\n"
        "    invoke-virtual {p0}, Lretrofit2/Response;->code()I\n\n"
        "    move-result p0\n\n"
        "    invoke-virtual {v0}, Lokhttp3/ResponseBody;->string()Ljava/lang/String;\n\n"
        "    move-result-object v0\n\n"
        f"    invoke-static {{p0, v0}}, {LOG}->http(ILjava/lang/String;)V\n\n"
        f"    invoke-static {{p0, v0}}, {LOG}->sessionInvalid(ILjava/lang/String;)Z\n\n"
        "    move-result p0\n\n"
        "    if-eqz p0, :cond_0\n\n"
        "    new-instance p0, Lca/vmedia/domain/error/ApiExceptions$NeedsReloginToResolveException;\n\n"
        "    invoke-direct {p0}, Lca/vmedia/domain/error/ApiExceptions$NeedsReloginToResolveException;-><init>()V\n\n"
        "    throw p0\n\n"
        "    :cond_0\n"
        "    new-instance p0, Lca/vmedia/domain/error/ApiExceptions$UnknownNetworkException;\n\n"
        "    invoke-direct {p0, v0}, Lca/vmedia/domain/error/ApiExceptions$UnknownNetworkException;-><init>(Ljava/lang/String;)V\n\n"
        "    throw p0\n\n"
    )
    return s[:a] + m.replace(old, new) + s[b:]


edit("ca/vmedia/api/implementations/error/ApiErrorHandlerKt.smali", fix_body)


# 3. A temporary network error / HTTP 500 during silent re-login used to delete
#    the saved login (refresh token + session) -> forced full login.
#    Skip that wipe; keep the token so the next attempt can succeed.
WIPE = re.compile(
    r"(\n    instance-of (\w+), (\w+), Lca/vmedia/domain/error/ApiExceptions\$UnknownNetworkException;\n)"
    r"(.*?handleBadRequestRefreshTokenState\(\)V\n\n(?:    \.line \d+\n)?    (:cond_\w+)\n)",
    re.S)


def skip_wipe(s):
    count = 0

    def rep(m):
        nonlocal count
        count += 1
        reg, label = m.group(3), m.group(5)
        return (f"\n    invoke-static {{{reg}}}, {LOG}->e(Ljava/lang/Throwable;)V\n\n"
                f"    const-string {m.group(2)}, \"Re-login failed temporarily; keeping saved login\"\n\n"
                f"    invoke-static {{{m.group(2)}}}, {LOG}->event(Ljava/lang/String;)V\n\n"
                f"    goto {label}\n" + m.group(1) + m.group(4))

    out = WIPE.sub(rep, s)
    assert count >= 1, "wipe block not found"
    print("   wipe blocks skipped:", count)
    return out


edit("ca/vmedia/domain/error/ReLoginHandleHandler.smali", skip_wipe)
edit("ca/vmedia/domain/error/UseCaseErrorHandlerKt.smali", skip_wipe)


# 4. Logging out (also triggered automatically when a session expires) wiped
#    player settings, genre filter and parental-control settings. Keep them.
def keep_settings(s):
    for call in (
        "Lca/vmedia/domain/repository/GenresRepository;->clearGenresCache()V",
        "Lca/vmedia/domain/repository/PlayerSettingsRepository;->clearPlayerSettingsCache()V",
        "Lca/vmedia/domain/repository/ParentControlRepository;->clearParentControlCache()V",
    ):
        s = re.sub(r"    invoke-interface \{(\w+)\}, " + re.escape(call) + "\n",
                   lambda m: f"    # VibeTV: removed {call.split('->')[1]}\n", s, count=1)
        assert call not in s, call
    return s


edit("ca/vmedia/domain/usecases/common/impl/LogOutUseCaseImpl$invoke$2.smali", keep_settings)


def log_logout(s):
    a, b = method(s, "public final invokeSuspend(")
    m = s[a:b]
    loc = re.search(r"    \.locals (\d+)\n", m)
    first = m.index("\n", loc.end() - 1) + 1
    inj = ('\n    const-string v0, "Logout running (manual or session expired)"\n\n'
           f"    invoke-static {{v0}}, {LOG}->event(Ljava/lang/String;)V\n\n")
    assert int(loc.group(1)) >= 1
    return s[:a] + m[:first] + inj + m[first:] + s[b:]


edit("ca/vmedia/domain/usecases/common/impl/LogOutUseCaseImpl$invoke$2.smali", log_logout)


# 5. Log why the app kicks you to the login screen.
def log_states(s):
    for name in ("handleInvalidTokenState()V", "handleBadRequestRefreshTokenState()V",
                 "handleInvalidWebTokenState()V"):
        a, b = method(s, "public " + name)
        m = s[a:b]
        loc = re.search(r"    \.locals (\d+)\n", m)
        assert int(loc.group(1)) >= 1
        inj = (f'\n    const-string v0, "Forced sign-out: {name[:-3]}"\n\n'
               f"    invoke-static {{v0}}, {LOG}->event(Ljava/lang/String;)V\n")
        s = s[:a] + m[:loc.end()] + inj + m[loc.end():] + s[b:]
    return s


edit("ca/vmedia/tv/models/NetworkManagerImpl.smali", log_states)


# 6. Encrypted credential store self-deletes when Android Keystore hiccups
#    (common after backup/restore or OS updates). Use normal private prefs.
def plain_prefs(s):
    a, b = method(s, "private final provideEncryptedPreferences(")
    new = (".method private final provideEncryptedPreferences()Landroid/content/SharedPreferences;\n"
           "    .locals 3\n\n"
           "    iget-object v0, p0, Lca/vmedia/persistence/SecuredUserCredentialsPersistence;->context:Landroid/content/Context;\n\n"
           '    const-string v1, "crprefs_plain"\n\n'
           "    const/4 v2, 0x0\n\n"
           "    invoke-virtual {v0, v1, v2}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;\n\n"
           "    move-result-object v0\n\n"
           "    return-object v0\n"
           ".end method")
    return s[:a] + new + s[b:]


edit("ca/vmedia/persistence/SecuredUserCredentialsPersistence.smali", plain_prefs)


def rm_creds(s):
    # removeCredentials deleted "crprefs" (old name); delete the file actually in use.
    a, b = method(s, "public removeCredentials()")
    m = s[a:b]
    return s[:a] + m.replace('"crprefs"', '"crprefs_plain"') + s[b:]


edit("ca/vmedia/persistence/SecuredUserCredentialsPersistence.smali", rm_creds)

# ---- Buffering ----
TUNE = "Lca/vibetv/VibeTune;"

edit("ca/vmedia/tv/App.smali", lambda s: replace_once(
    s,
    f"    invoke-static {{p0}}, {LOG}->init(Landroid/content/Context;)V\n",
    f"    invoke-static {{p0}}, {LOG}->init(Landroid/content/Context;)V\n\n"
    f"    invoke-static {{p0}}, {TUNE}->init(Landroid/content/Context;)V\n"))


# 7. A failed video chunk download was never retried (retry count 0, no retry
#    delay): one dropped request stopped playback and the app reloaded the whole
#    stream. Retry up to 5 times, 1-3 s apart (not for 4xx like 403, which the
#    app handles as "IP changed").
def retry_policy(s):
    a, b = method(s, "public getMinimumLoadableRetryCount(I)I")
    s = s[:a] + (".method public getMinimumLoadableRetryCount(I)I\n"
                 "    .locals 0\n\n"
                 f"    invoke-static {{p1}}, {TUNE}->retryCount(I)I\n\n"
                 "    move-result p1\n\n"
                 "    return p1\n"
                 ".end method") + s[b:]
    sig = "public getRetryDelayMsFor(Lcom/google/android/exoplayer2/upstream/LoadErrorHandlingPolicy$LoadErrorInfo;)J"
    a, b = method(s, sig)
    s = s[:a] + (f".method {sig}\n"
                 "    .locals 2\n\n"
                 f"    invoke-static {{p1}}, {TUNE}->retryDelayMs(Lcom/google/android/exoplayer2/upstream/LoadErrorHandlingPolicy$LoadErrorInfo;)J\n\n"
                 "    move-result-wide v0\n\n"
                 "    return-wide v0\n"
                 ".end method") + s[b:]
    return s


edit("ca/vmedia/utils/MediaSourceManager$getMyErrorHandlingPolicy$1.smali", retry_policy)


# 8. A stuck video download waited up to 60 s (read) / 40 s (connect) before
#    failing. Give up after 12 s / 10 s so it's retried quickly.
def timeouts(s):
    a, b = method(s, "private final makeDefaultHttpClientBuilder(Z)")
    m = s[a:b]
    m = replace_once(m, "    const-wide/16 v2, 0x3c\n", "    const-wide/16 v2, 0xc\n")
    m = replace_once(m, "    const-wide/16 v0, 0x28\n", "    const-wide/16 v0, 0xa\n")
    return s[:a] + m + s[b:]


edit("ca/vmedia/okhttp/OkHttpClientsImpl.smali", timeouts)


# 9. After 3 stream reloads the app shows "Try again", and the counter was never
#    reset when playback recovered, so 3 blips in a long session were enough.
#    A reload more than a minute after the previous one starts from 0 again.
def restart_counter(s):
    a, b = method(s, "private final restartPlayerAfterError()V")
    m = s[a:b]
    loc = re.search(r"    \.locals (\d+)\n", m)
    assert int(loc.group(1)) >= 1
    field = "Lca/vmedia/player/PlayerViewBase;->restartAttempt:I"
    inj = (f"\n    iget v0, p0, {field}\n\n"
           f"    invoke-static {{v0}}, {TUNE}->restartCount(I)I\n\n"
           "    move-result v0\n\n"
           f"    iput v0, p0, {field}\n")
    return s[:a] + m[:loc.end()] + inj + m[loc.end():] + s[b:]


edit("ca/vmedia/player/PlayerViewBase.smali", restart_counter)


# 10. Optional (Settings > VibeTV, off by default): play live TV 30 s behind
#     live for a bigger buffer. Streams get their MediaItem from VibeTune.
def media_items(s):
    old = "invoke-static {p1}, Lcom/google/android/exoplayer2/MediaItem;->fromUri(Landroid/net/Uri;)Lcom/google/android/exoplayer2/MediaItem;"
    assert s.count(old) >= 3, s.count(old)
    return s.replace(old, f"invoke-static {{p1}}, {TUNE}->mediaItem(Landroid/net/Uri;)Lcom/google/android/exoplayer2/MediaItem;")


edit("ca/vmedia/utils/MediaSourceManager.smali", media_items)

edit("ca/vmedia/phones_tablets_ui/settings/SettingsFragment.smali", lambda s: replace_once(
    s,
    "    invoke-super {p0, p1, p2}, Lca/vmedia/phones_tablets_ui/BaseFragment;->onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V\n",
    "    invoke-super {p0, p1, p2}, Lca/vmedia/phones_tablets_ui/BaseFragment;->onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V\n\n"
    f"    invoke-static {{p1}}, {TUNE}->addSettings(Landroid/view/View;)V\n"))

# Note: AndroidManifest.xml / resources are intentionally NOT modified.
# The build decodes with `apktool d -r`, keeping the app's original resources
# byte-for-byte. v1 rebuilt resources and crashed after login; v2 (original
# resources) does not, so resource rebuilding was most likely the cause.
# (So allowBackup="true" stays; turning it off would need a binary-manifest edit.)
