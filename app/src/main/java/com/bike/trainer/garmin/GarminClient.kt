package com.bike.trainer.garmin

import android.util.Base64
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Long-lived OAuth1 token from a Garmin login (good ~1 year). */
data class GarminOAuth1(val token: String, val tokenSecret: String)

sealed interface GarminLoginResult {
    data class Success(val oauth1: GarminOAuth1) : GarminLoginResult
    /** The account has 2FA on; call [GarminClient.resumeMfa] with the emailed/app code. */
    object MfaRequired : GarminLoginResult
    data class Error(val message: String) : GarminLoginResult
}

sealed interface GarminUploadResult {
    data class Success(val detail: String) : GarminUploadResult
    object Duplicate : GarminUploadResult
    object NotConnected : GarminUploadResult
    data class Error(val message: String) : GarminUploadResult
}

/**
 * Best-effort Garmin Connect client over the *unofficial* mobile SSO + upload
 * service (the same flow apps like RunGap / python-garminconnect use). Garmin has
 * no public activity-upload API, and this flow is undocumented and changes from
 * time to time, so everything is isolated here and fails soft.
 */
class GarminClient {

    // Per-login cookie jar so the MFA step reuses the sign-in session.
    private val cookies = HashMap<String, MutableList<Cookie>>()
    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, list: List<Cookie>) {
            cookies.getOrPut(url.host) { mutableListOf() }.apply {
                removeAll { old -> list.any { it.name == old.name } }
                addAll(list)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies[url.host] ?: emptyList()
    }

    private val http = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var consumerKey: String = ""
    private var consumerSecret: String = ""

    // ---------------------------------------------------------------- login

    suspend fun login(email: String, password: String): GarminLoginResult =
        withContext(Dispatchers.IO) {
            runCatching {
                loadConsumer()
                cookies.clear()
                // Prime cookies from the mobile sign-in page (Cloudflare + session).
                http.newCall(
                    Request.Builder()
                        .url("$SSO/mobile/sso/en/sign-in?clientId=$CLIENT_ID")
                        .header("User-Agent", BROWSER_UA)
                        .get().build(),
                ).execute().close()

                val url = "$SSO/mobile/api/login".toHttpUrlWithParams(
                    "clientId" to CLIENT_ID, "locale" to "en-US", "service" to SERVICE_URL,
                )
                val body = JSONObject()
                    .put("username", email).put("password", password)
                    .put("rememberMe", false).put("captchaToken", "")
                    .toString().toRequestBody("application/json".toMediaType())
                val resp = http.newCall(
                    Request.Builder().url(url)
                        .header("User-Agent", BROWSER_UA)
                        .header("origin", "https://sso.garmin.com")
                        .post(body).build(),
                ).execute()
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@runCatching GarminLoginResult.Error("Login failed (${resp.code})")
                val json = JSONObject(text)
                val type = json.optJSONObject("responseStatus")?.optString("type")
                when (type) {
                    "SUCCESSFUL" -> {
                        val ticket = json.optString("serviceTicketId").ifBlank {
                            return@runCatching GarminLoginResult.Error("No service ticket")
                        }
                        GarminLoginResult.Success(ticketToOAuth1(ticket))
                    }
                    "MFA_REQUIRED" -> GarminLoginResult.MfaRequired
                    else -> GarminLoginResult.Error("Login rejected${type?.let { " ($it)" } ?: ""}")
                }
            }.getOrElse { GarminLoginResult.Error(it.message ?: "Garmin login error") }
        }

    /** Continue a login that returned [GarminLoginResult.MfaRequired]. */
    suspend fun resumeMfa(code: String): GarminLoginResult = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$SSO/mobile/api/mfa/verifyCode".toHttpUrlWithParams(
                "clientId" to CLIENT_ID, "locale" to "en-US", "service" to SERVICE_URL,
            )
            val body = JSONObject().put("mfaCode", code.trim())
                .toString().toRequestBody("application/json".toMediaType())
            val resp = http.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", BROWSER_UA)
                    .header("origin", "https://sso.garmin.com")
                    .post(body).build(),
            ).execute()
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return@runCatching GarminLoginResult.Error("MFA failed (${resp.code})")
            val ticket = JSONObject(text).optString("serviceTicketId").ifBlank {
                return@runCatching GarminLoginResult.Error("MFA: no service ticket")
            }
            GarminLoginResult.Success(ticketToOAuth1(ticket))
        }.getOrElse { GarminLoginResult.Error(it.message ?: "Garmin MFA error") }
    }

    private fun ticketToOAuth1(ticket: String): GarminOAuth1 {
        val url = "$CONNECT_API/oauth-service/oauth/preauthorized"
        val params = linkedMapOf(
            "ticket" to ticket,
            "login-url" to LOGIN_URL,
            "accepts-mfa-tokens" to "true",
        )
        val resp = http.newCall(
            Request.Builder().url(url.toHttpUrlWithParams(*params.toList().toTypedArray()))
                .header("User-Agent", OAUTH_UA)
                .header("Authorization", oauthHeader("GET", url, params, null, null))
                .get().build(),
        ).execute()
        val text = resp.body?.string().orEmpty()
        resp.close()
        val parsed = parseQuery(text)
        return GarminOAuth1(
            token = parsed["oauth_token"] ?: error("no oauth_token"),
            tokenSecret = parsed["oauth_token_secret"] ?: error("no oauth_token_secret"),
        )
    }

    // ---------------------------------------------------------------- upload

    /** Upload an activity file. [extension] e.g. "tcx". */
    suspend fun upload(oauth1: GarminOAuth1, fileName: String, bytes: ByteArray): GarminUploadResult =
        withContext(Dispatchers.IO) {
            runCatching {
                loadConsumer()
                val bearer = exchange(oauth1) ?: return@runCatching GarminUploadResult.Error("Garmin re-auth failed")
                val ext = fileName.substringAfterLast('.', "tcx")
                val media = "application/octet-stream".toMediaType()
                val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, bytes.toRequestBody(media))
                    .build()
                val resp = http.newCall(
                    Request.Builder()
                        .url("$CONNECT_API/upload-service/upload/.$ext")
                        .header("Authorization", "Bearer $bearer")
                        .header("User-Agent", OAUTH_UA)
                        .header("NK", "NT")
                        .post(multipart).build(),
                ).execute()
                val payload = resp.body?.string().orEmpty()
                when (resp.code) {
                    200, 201, 202 -> GarminUploadResult.Success("Uploaded to Garmin Connect")
                    409 -> GarminUploadResult.Duplicate
                    else -> GarminUploadResult.Error("Garmin upload failed (${resp.code}): ${payload.take(200)}")
                }
            }.getOrElse { GarminUploadResult.Error(it.message ?: "Garmin upload error") }
        }

    /** Exchange the stored OAuth1 token for a short-lived OAuth2 bearer. */
    private fun exchange(oauth1: GarminOAuth1): String? {
        val url = "$CONNECT_API/oauth-service/oauth/exchange/user/2.0"
        val bodyParams = linkedMapOf("audience" to "GARMIN_CONNECT_MOBILE_ANDROID_DI")
        val auth = oauthHeader("POST", url, bodyParams, oauth1.token, oauth1.tokenSecret)
        val form = bodyParams.entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }.toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val resp = http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", OAUTH_UA)
                .header("Authorization", auth)
                .post(form).build(),
        ).execute()
        val text = resp.body?.string().orEmpty()
        resp.close()
        if (!resp.isSuccessful) return null
        return JSONObject(text).optString("access_token").ifBlank { null }
    }

    // ---------------------------------------------------------------- helpers

    private fun loadConsumer() {
        if (consumerKey.isNotEmpty()) return
        val resp = http.newCall(Request.Builder().url(OAUTH_CONSUMER_URL).get().build()).execute()
        val json = JSONObject(resp.body?.string().orEmpty())
        resp.close()
        consumerKey = json.getString("consumer_key")
        consumerSecret = json.getString("consumer_secret")
    }

    /** Build an OAuth1 HMAC-SHA1 Authorization header over [extraParams] + the OAuth fields. */
    private fun oauthHeader(
        method: String,
        url: String,
        extraParams: Map<String, String>,
        token: String?,
        tokenSecret: String?,
    ): String {
        val oauth = sortedMapOf(
            "oauth_consumer_key" to consumerKey,
            "oauth_nonce" to Random.nextLong().toULong().toString(16) + System.nanoTime().toString(16),
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "oauth_version" to "1.0",
        )
        if (token != null) oauth["oauth_token"] = token
        // Signature base string includes both the OAuth fields and the request params.
        val all = sortedMapOf<String, String>()
        all.putAll(oauth)
        all.putAll(extraParams)
        val paramStr = all.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val base = "${method.uppercase()}&${enc(url)}&${enc(paramStr)}"
        val signingKey = "${enc(consumerSecret)}&${enc(tokenSecret ?: "")}"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(signingKey.toByteArray(), "HmacSHA1"))
        val sig = Base64.encodeToString(mac.doFinal(base.toByteArray()), Base64.NO_WRAP)
        oauth["oauth_signature"] = sig
        return "OAuth " + oauth.entries.joinToString(", ") { "${enc(it.key)}=\"${enc(it.value)}\"" }
    }

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20").replace("*", "%2A")
            .replace("%7E", "~")

    private fun parseQuery(s: String): Map<String, String> =
        s.split("&").mapNotNull {
            val i = it.indexOf('='); if (i < 0) null else
                URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()

    private fun String.toHttpUrlWithParams(vararg params: Pair<String, String>): HttpUrl {
        val b = this.toHttpUrl().newBuilder()
        params.forEach { b.addQueryParameter(it.first, it.second) }
        return b.build()
    }

    private companion object {
        const val SSO = "https://sso.garmin.com/sso"
        const val CONNECT_API = "https://connectapi.garmin.com"
        const val CLIENT_ID = "GCM_ANDROID_DARK"
        const val SERVICE_URL = "https://connect.garmin.com/modern"
        const val LOGIN_URL = "https://mobile.integration.garmin.com/gcm/android"
        const val OAUTH_CONSUMER_URL = "https://thegarth.s3.amazonaws.com/oauth_consumer.json"
        const val OAUTH_UA = "com.garmin.android.apps.connectmobile"
        const val BROWSER_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Mobile/15E148"
    }
}
