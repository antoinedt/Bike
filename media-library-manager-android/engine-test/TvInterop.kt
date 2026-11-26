// Checks the TV tab's Fizz client (FizzTvClient.kt) against the fake Fizz server in
// ../../media-library-manager/test/fakeFizz.mjs: both sign-in kinds, the saved login surviving
// restarts and network errors, session renewal, and the "My plan" channels + guide.
import com.medialibrary.manager.tv.BrowserSignIn
import com.medialibrary.manager.tv.FizzConfig
import com.medialibrary.manager.tv.FizzTvClient
import com.medialibrary.manager.tv.SignInRequiredException
import com.medialibrary.manager.tv.TvCredentialStore
import com.medialibrary.manager.tv.TvCredentials
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private class MemoryStore : TvCredentialStore {
    var value: TvCredentials? = null
    override fun load() = value
    override fun save(credentials: TvCredentials?) { value = credentials }
}

private fun ok(cond: Boolean, what: String) {
    if (!cond) { System.err.println("FAIL: $what"); kotlin.system.exitProcess(1) }
    println("ok - $what")
}

private fun get(url: String): String = (URL(url).openConnection() as HttpURLConnection).run { inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }

fun main(args: Array<String>) {
    val info = JSONObject(args[0])
    val config = FizzConfig(
        oktaBase = info.getString("oktaBase"), apiBase = info.getString("apiBase"),
        browserClientId = info.getString("browserClientId"), deviceClientId = info.getString("deviceClientId"),
        imageBase = info.getString("imageBase")
    )
    val control = info.getString("control")
    val store = MemoryStore()
    val client = FizzTvClient(store, { "0123456789abcdef" }, config)

    // Device-code sign-in (TV).
    val code = client.startDeviceSignIn()
    ok(code.userCode == "ABCD-EFGH" && code.verificationUriComplete!!.contains("ABCD-EFGH"), "device sign-in shows a code")
    ok(!client.pollDeviceSignIn(code), "still waiting before approval")
    ok(client.pollDeviceSignIn(code), "signed in after approval")
    ok(store.value?.refreshToken == "rt1" && store.value?.sessionId == "s1" && store.value?.email == "viewer@example.com", "login saved (refresh token, session, email)")

    // My plan channels.
    val channels = client.myPlanChannels()
    ok(channels.map { it.id } == listOf(10, 20), "my plan channels sorted by number, hidden ones dropped")
    ok(channels[0].logoUrl == "https://images.example/api/images?id=500&t=4", "channel logo url")

    // Guide.
    val from = 1_800_000_000_000L
    val programs = client.guide(channels.map { it.id }, from, from + 90 * 60_000L)
    ok(programs.size == 6, "guide returns programs for both channels")
    val first = programs.first { it.channelId == 10 }
    ok(first.title == "Morning Show" && first.episode == "S2 E1 · Episode 1" && first.end - first.start == 30 * 60_000L, "program title, episode and length")

    // Session expired on the server: renewed with the refresh token, transparently.
    get("$control/expire")
    val again = client.guide(listOf(20), from, from + 3600_000L)
    ok(again.isNotEmpty() && store.value?.sessionId == "s2" && store.value?.refreshToken == "rt2", "expired session renewed with refresh token (rotated)")

    // A new app start keeps the login (a new client on the same store).
    val restarted = FizzTvClient(store, { "ffffffffffffffff" }, config)
    ok(restarted.myPlanChannels().size == 2 && store.value?.deviceId == "0123456789abcdef", "login kept after restart, same device id")

    // Network down: the saved login is kept.
    val offline = FizzTvClient(store, { "x" }, config.copy(apiBase = "http://127.0.0.1:1"))
    val offlineError = runCatching { offline.myPlanChannels(forceRefresh = true) }.exceptionOrNull()
    ok(offlineError != null && offlineError !is SignInRequiredException && store.value != null, "network error keeps the saved login")

    // Refresh token revoked: signed out, sign-in screen.
    get("$control/revoke")
    get("$control/expire")
    val revoked = runCatching { client.myPlanChannels(forceRefresh = true) }.exceptionOrNull()
    ok(revoked is SignInRequiredException && store.value == null, "revoked refresh token signs out")

    // Browser sign-in (phone) with PKCE.
    val pending: BrowserSignIn = client.startBrowserSignIn()
    ok(pending.url.contains("code_challenge_method=S256") && pending.url.contains("redirect_uri=fizztv%3A%2F%2Fcallback"), "browser sign-in url")
    val conn = (URL(pending.url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false }
    val redirect = conn.getHeaderField("Location")
    ok(client.isRedirect(redirect), "redirect recognised")
    ok(runCatching { client.finishBrowserSignIn(redirect.replace(Regex("state=[^&]+"), "state=wrong"), pending) }.isFailure, "wrong state refused")
    val conn2 = (URL(pending.url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false }
    client.finishBrowserSignIn(conn2.getHeaderField("Location"), pending)
    ok(store.value != null && client.myPlanChannels(forceRefresh = true).size == 2, "browser sign-in works")

    // Session expired while the guide loads several requests at once: renewed once, still signed in.
    get("$control/expire")
    val before = JSONObject(get("$control/state")).getJSONArray("logins").length()
    val threads = (1..6).map { i -> Thread { client.guide(listOf(if (i % 2 == 0) 10 else 20), from, from + 3600_000L) }.apply { start() } }
    threads.forEach { it.join() }
    val after = JSONObject(get("$control/state")).getJSONArray("logins").length()
    ok(after - before == 1 && store.value != null, "parallel requests after expiry renew once and stay signed in")
    ok(FizzTvClient.parseTime("/Date(1795680000000+0000)/") == 1795680000000L && FizzTvClient.parseTime("/Date(1795680000000-0500)/") == 1795680000000L, "Fizz's /Date(ms+zone)/ times")
    ok(FizzTvClient.parseTime("2026-11-26T05:00:00") == 1795669200000L && FizzTvClient.parseTime("2026-11-26T05:00:00-05:00") == 1795687200000L, "guide times without a zone are UTC")

    val big = client.guide((100 until 112).toList(), 0L, 24 * 3600_000L)
    ok(big.size == 576, "a guide bigger than one page (500) is fetched in pages")

    val logins = JSONObject(get("$control/state")).getJSONArray("logins")
    ok((0 until logins.length()).all { logins.getJSONObject(it).getString("appName") == "F-GOOGLE-PLAY-MOBILE" }, "logins identify as the Fizz phone app")
    println("TV interop: all passed")
}
