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

# Note: AndroidManifest.xml / resources are intentionally NOT modified.
# The build decodes with `apktool d -r`, keeping the app's original resources
# byte-for-byte. v1 rebuilt resources and crashed after login; v2 (original
# resources) does not, so resource rebuilding was most likely the cause.
# (So allowBackup="true" stays; turning it off would need a binary-manifest edit.)
