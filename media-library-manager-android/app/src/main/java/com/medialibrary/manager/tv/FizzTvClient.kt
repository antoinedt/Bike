package com.medialibrary.manager.tv

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64

/** Where the Fizz TV service lives. Only tests change it. */
data class FizzConfig(
    val oktaBase: String = "https://auth.fizz.ca/oauth2/aus4ah1q96x2bjArV697/v1",
    val apiBase: String = "https://a2.vmedia.ca",
    /** Phone app's Okta client: browser sign-in (code + PKCE). */
    val browserClientId: String = "0oa4ah2n6j5msTWe4697",
    /** TV app's Okta client: "enter this code on your phone" sign-in. */
    val deviceClientId: String = "0oa4agzobqaFHJ1ob697",
    val redirectUri: String = "fizztv://callback",
    val scope: String = "openid email profile offline_access",
    val appName: String = "F-GOOGLE-PLAY-MOBILE",
    val siteId: Int = 176,
    val imageBase: String = "https://images.cdn.vmedia.ca/api/images"
)

/** What stays saved between launches. */
data class TvCredentials(val refreshToken: String, val sessionId: String?, val email: String?, val deviceId: String)

interface TvCredentialStore {
    fun load(): TvCredentials?
    fun save(credentials: TvCredentials?)
}

data class TvChannel(val id: Int, val number: Int?, val name: String, val logoUrl: String?)

data class TvProgram(
    val key: String,
    val channelId: Int,
    val title: String,
    val episode: String?,
    val description: String?,
    /** Epoch milliseconds. */
    val start: Long,
    val end: Long,
    val imageUrl: String?
)

data class BrowserSignIn(val url: String, val state: String, val verifier: String)

data class DeviceCode(val userCode: String, val verificationUri: String, val verificationUriComplete: String?, val deviceCode: String, val intervalSeconds: Int, val expiresAt: Long)

/** The saved sign-in is gone or was refused: show the sign-in screen. */
class SignInRequiredException(message: String = "Sign in to your Fizz account.") : Exception(message)

/**
 * The bits of the Fizz TV app this app uses: sign-in (Okta, then the VMedia platform's own session)
 * and the guide for the channels in your plan. Same calls and saved login as the phone app: a
 * refresh token that is kept until the server says it's invalid (a network error never wipes it),
 * and a session id that's renewed with it when the server says the session expired.
 *
 * Plain Kotlin + org.json (no Android), so it's tested on a desktop JVM against a fake server.
 */
class FizzTvClient(private val store: TvCredentialStore, private val newDeviceId: () -> String, val config: FizzConfig = FizzConfig()) {
    private val random = SecureRandom()
    private var channelCache: List<TvChannel>? = null

    fun credentials(): TvCredentials? = store.load()

    fun isSignedIn(): Boolean = store.load() != null

    // ---- Sign-in in a browser (WebView / Electron window) ----

    fun startBrowserSignIn(): BrowserSignIn {
        val verifier = randomToken(48)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomToken(16)
        val url = "${config.oktaBase}/authorize?" + form(
            "client_id" to config.browserClientId,
            "response_type" to "code",
            "scope" to config.scope,
            "redirect_uri" to config.redirectUri,
            "state" to state,
            "nonce" to randomToken(16),
            "code_challenge" to challenge,
            "code_challenge_method" to "S256"
        )
        return BrowserSignIn(url, state, verifier)
    }

    /** True for the address Okta sends the browser to once you've signed in. */
    fun isRedirect(url: String) = url.startsWith(config.redirectUri)

    /** Finishes a browser sign-in from the redirect address it ended on. */
    fun finishBrowserSignIn(redirectUrl: String, pending: BrowserSignIn) {
        val params = queryParams(redirectUrl)
        params["error"]?.let { throw IOException(params["error_description"] ?: it) }
        if (params["state"] != pending.state) throw IOException("Sign-in was interrupted. Please try again.")
        val code = params["code"] ?: throw IOException("Sign-in didn't return a code.")
        val token = postForm(
            "${config.oktaBase}/token",
            "grant_type" to "authorization_code",
            "client_id" to config.browserClientId,
            "redirect_uri" to config.redirectUri,
            "code" to code,
            "code_verifier" to pending.verifier
        )
        val accessToken = token.optString("access_token").ifEmpty { throw IOException(oktaError(token) ?: "Sign-in failed.") }
        login(accessToken = accessToken)
    }

    // ---- Sign-in with a code shown on screen (for the TV) ----

    fun startDeviceSignIn(): DeviceCode {
        val r = postForm("${config.oktaBase}/device/authorize", "client_id" to config.deviceClientId, "scope" to config.scope)
        val deviceCode = r.optString("device_code").ifEmpty { throw IOException(oktaError(r) ?: "Couldn't get a sign-in code.") }
        return DeviceCode(
            userCode = r.getString("user_code"),
            verificationUri = r.optString("verification_uri"),
            verificationUriComplete = r.optString("verification_uri_complete").ifEmpty { null },
            deviceCode = deviceCode,
            intervalSeconds = r.optInt("interval", 5).coerceAtLeast(1),
            expiresAt = System.currentTimeMillis() + r.optLong("expires_in", 600) * 1000
        )
    }

    /** One check: true once you've approved the code (and you're signed in), false while waiting. */
    fun pollDeviceSignIn(code: DeviceCode): Boolean {
        if (System.currentTimeMillis() > code.expiresAt) throw IOException("The code expired. Get a new one.")
        val r = postForm(
            "${config.oktaBase}/token",
            "client_id" to config.deviceClientId,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            "device_code" to code.deviceCode
        )
        val accessToken = r.optString("access_token")
        if (accessToken.isNotEmpty()) {
            login(accessToken = accessToken)
            return true
        }
        return when (r.optString("error")) {
            "authorization_pending", "slow_down" -> false
            "expired_token" -> throw IOException("The code expired. Get a new one.")
            "access_denied" -> throw IOException("Sign-in was declined.")
            else -> throw IOException(oktaError(r) ?: "Sign-in failed.")
        }
    }

    fun signOut() {
        channelCache = null
        store.save(null)
    }

    // ---- Guide ----

    /** The channels in your plan ("My plan" in the Fizz app), in channel-number order. */
    fun myPlanChannels(forceRefresh: Boolean = false): List<TvChannel> {
        if (!forceRefresh) channelCache?.let { return it }
        val body = withSession { session ->
            val request = JSONObject()
                .put("type", "4")
                .put("filter", JSONObject().put("contentType", "4").put("availableOnly", true).put("HasImagesOnly", false))
                .put("paging", JSONObject().put("itemsOnPage", 1000).put("pageNumber", 1))
                .put("UseUtcTime", true)
            http("POST", "${config.apiBase}/api/channel/client", JSONObject().put("request", request).put("sessionID", session).toString())
        }
        val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
        val channels = (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optBoolean("visible", true) }
            .map { c ->
                TvChannel(
                    id = c.getInt("id"),
                    number = c.optIntOrNull("accessNum") ?: c.optIntOrNull("orderNum"),
                    name = c.optString("name").ifEmpty { c.optString("CallSign") },
                    logoUrl = imageUrl(c.optJSONArray("Images"), listOf(8, 4, 10))
                )
            }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.number ?: Int.MAX_VALUE }, { it.name }))
        channelCache = channels
        return channels
    }

    /** Programs on [channelIds] between [from] and [to] (epoch ms). */
    fun guide(channelIds: List<Int>, from: Long, to: Long): List<TvProgram> {
        if (channelIds.isEmpty()) return emptyList()
        val ids = channelIds.distinct().sorted().joinToString(",")
        val body = withSession { session ->
            http(
                "GET",
                "${config.apiBase}/api/v3/guide/client?" + form(
                    "ChannelIds" to ids, "StartDate" to from.toString(), "EndDate" to to.toString(),
                    "PageNumber" to "1", "PageSize" to "1000"
                ),
                headers = mapOf("X-Session-Id" to session)
            )
        }
        val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val p = items.getJSONObject(i)
            val air = p.optJSONObject("recentAirTime") ?: return@mapNotNull null
            val channelId = air.optIntOrNull("channelID") ?: return@mapNotNull null
            val start = runCatching { OffsetDateTime.parse(air.getString("startTime")).toInstant().toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            val minutes = p.optInt("length", 0).coerceAtLeast(1)
            val series = p.optString("SeriesName").ifEmpty { null }
            val name = p.optString("name").ifEmpty { p.optString("shortName") }
            val title = series ?: name
            val season = p.optIntOrNull("SeasonNumber")
            val ep = p.optIntOrNull("episodeNum")
            val epLabel = listOfNotNull(
                if (season != null && ep != null) "S${season} E${ep}" else null,
                name.takeIf { series != null && it.isNotEmpty() && it != series }
            ).joinToString(" · ").ifEmpty { null }
            TvProgram(
                key = "${air.optInt("id", 0)}-${p.optInt("id", 0)}-$start",
                channelId = channelId,
                title = title.ifEmpty { "—" },
                episode = epLabel,
                description = p.optString("description").ifEmpty { p.optString("shortDescription") }.ifEmpty { null },
                start = start,
                end = start + minutes * 60_000L,
                imageUrl = imageUrl(p.optJSONArray("Images"), listOf(29, 28)) ?: imageUrl(p.optJSONArray("SeriesImages"), listOf(29, 28))
            )
        }.filter { it.end > from && it.start < to }.distinctBy { it.key }.sortedBy { it.start }
    }

    // ---- Session handling ----

    /** Runs [call] with the saved session, renewing it with the refresh token when the server refuses it. */
    private fun withSession(call: (String) -> String): String {
        val saved = store.load() ?: throw SignInRequiredException()
        val session = saved.sessionId ?: refresh(saved)
        try {
            return call(session)
        } catch (e: HttpException) {
            if (e.code !in listOf(401, 403, 500)) throw e
        }
        // The server says the session is no longer valid (it does that with an HTTP 500): renew it once.
        val renewed = refresh(store.load() ?: throw SignInRequiredException())
        return try {
            call(renewed)
        } catch (e: HttpException) {
            throw IOException("The TV service didn't answer (${e.code}). Try again in a moment.")
        }
    }

    private fun refresh(saved: TvCredentials): String = login(refreshToken = saved.refreshToken, deviceId = saved.deviceId)

    /** The platform's own sign-in, with an Okta access token (first time) or the saved refresh token. Returns the session id. */
    private fun login(accessToken: String? = null, refreshToken: String? = null, deviceId: String? = null): String {
        val device = deviceId ?: store.load()?.deviceId ?: newDeviceId()
        val body = JSONObject()
            .put(
                "cc",
                JSONObject()
                    .put("clientCredentials", JSONObject().put("StbMacAddress", device))
                    .put("appSettings", JSONObject().put("appName", config.appName).put("siteID", config.siteId))
            )
        if (accessToken != null) body.put("AccessToken", accessToken)
        if (refreshToken != null) body.put("RefreshToken", refreshToken)
        val response = try {
            JSONObject(http("POST", "${config.apiBase}/api/client/login/okta", body.toString()))
        } catch (e: HttpException) {
            throw IOException("Couldn't sign in to the TV service (${e.code}).")
        }
        val settings = response.optJSONObject("appSettings")?.optJSONArray("settings")
        val errorCode = keyValue(settings, "errorCode")
        if (errorCode != null) {
            val message = keyValue(settings, "errorMessage") ?: errorCode
            if (refreshToken != null && errorCode.contains("REFRESH_TOKEN", ignoreCase = true)) {
                // Only a refresh token the server really rejects signs you out.
                signOut()
                throw SignInRequiredException("Your Fizz sign-in expired. Please sign in again.")
            }
            throw IOException(message)
        }
        val creds = response.optJSONObject("clientCredentials") ?: JSONObject()
        val session = creds.optString("sessionID").ifEmpty { throw IOException("The TV service didn't start a session.") }
        val newRefresh = response.optString("RefreshToken").ifEmpty { creds.optString("RefreshToken") }.ifEmpty { null }
            ?: refreshToken ?: throw IOException("The TV service didn't return a saved login.")
        val email = creds.optString("Email").ifEmpty { creds.optString("UserLogin") }.ifEmpty { null } ?: store.load()?.email
        store.save(TvCredentials(newRefresh, session, email, device))
        return session
    }

    // ---- HTTP ----

    class HttpException(val code: Int, val body: String) : IOException("HTTP $code")

    private fun http(method: String, url: String, json: String? = null, headers: Map<String, String> = emptyMap(), formBody: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val payload = json ?: formBody
            if (payload != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", if (json != null) "application/json; charset=utf-8" else "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(payload.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code >= 400) throw HttpException(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Okta replies with JSON on errors too (400 for "still waiting" etc.), so those are returned, not thrown. */
    private fun postForm(url: String, vararg fields: Pair<String, String>): JSONObject = try {
        JSONObject(http("POST", url, formBody = form(*fields)))
    } catch (e: HttpException) {
        runCatching { JSONObject(e.body) }.getOrElse { throw IOException("Sign-in service error (${e.code}).") }
    }

    private fun oktaError(r: JSONObject) = r.optString("error_description").ifEmpty { r.optString("error") }.ifEmpty { null }

    private fun imageUrl(images: JSONArray?, preferredTypes: List<Int>): String? {
        if (images == null || images.length() == 0) return null
        val list = (0 until images.length()).mapNotNull { images.optJSONObject(it) }
        val pick = preferredTypes.firstNotNullOfOrNull { t -> list.find { it.optString("Type").toIntOrNull() == t } } ?: return null
        return "${config.imageBase}?id=${pick.optString("Id")}&t=${pick.optString("Type")}"
    }

    private fun keyValue(list: JSONArray?, key: String): String? {
        if (list == null) return null
        for (i in 0 until list.length()) {
            val kv = list.optJSONObject(i) ?: continue
            if (kv.optString("key") == key) return kv.optString("value").ifEmpty { null }
        }
        return null
    }

    private fun randomToken(bytes: Int) = b64url(ByteArray(bytes).also(random::nextBytes))

    companion object {
        fun b64url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

        fun form(vararg fields: Pair<String, String>) =
            fields.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8").replace("+", "%20")}" }

        fun queryParams(url: String): Map<String, String> {
            val query = url.substringAfter('?', "").substringBefore('#')
            return query.split("&").filter { it.contains('=') }.associate {
                java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
        }

        /** A device id like the Fizz app's (Android's 16-hex-digit ANDROID_ID). */
        fun randomDeviceId(): String = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    }
}

private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optString(key).toIntOrNull() else null
