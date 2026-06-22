package com.bike.trainer.strava

import com.bike.trainer.BuildConfig
import com.bike.trainer.data.ProfileRepository
import com.bike.trainer.data.StravaAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Per-rider Strava: credentials + OAuth tokens live on the active profile, so
 * each rider connects their own account. Performs the token exchange/refresh and
 * the activity upload. All network calls run on [Dispatchers.IO].
 */
class StravaRepository(private val profiles: ProfileRepository) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    val isConnected: Flow<Boolean> = profiles.active.map { it?.strava?.connected == true }

    val isConfigured: Flow<Boolean> = profiles.active.map {
        val (id, secret) = effectiveCredentials(it?.strava ?: StravaAccount())
        id.isNotBlank() && secret.isNotBlank()
    }

    /** Effective client id/secret for the rider, falling back to build-time defaults. */
    private fun effectiveCredentials(account: StravaAccount): Pair<String, String> {
        val id = account.clientId.ifBlank { BuildConfig.STRAVA_CLIENT_ID }
        val secret = account.clientSecret.ifBlank { BuildConfig.STRAVA_CLIENT_SECRET }
        return id to secret
    }

    private suspend fun activeAccount(): StravaAccount =
        profiles.current().active?.strava ?: StravaAccount()

    /** OAuth authorize URL for the active rider, or null if not configured. */
    suspend fun authorizeUrl(): String? {
        val (id, secret) = effectiveCredentials(activeAccount())
        return if (id.isNotBlank() && secret.isNotBlank()) StravaConfig.authorizeUrl(id) else null
    }

    suspend fun exchangeAuthorizationCode(code: String): Boolean = withContext(Dispatchers.IO) {
        val (id, secret) = effectiveCredentials(activeAccount())
        if (id.isBlank() || secret.isBlank()) return@withContext false
        val body = FormBody.Builder()
            .add("client_id", id)
            .add("client_secret", secret)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .build()
        val request = Request.Builder().url(StravaConfig.TOKEN_URL).post(body).build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val tokens = json.decodeFromString<TokenResponse>(resp.body?.string().orEmpty())
                profiles.setActiveStravaTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt)
                true
            }
        }.getOrDefault(false)
    }

    suspend fun disconnect() = profiles.clearActiveStravaTokens()

    suspend fun uploadTcx(
        name: String,
        description: String,
        tcx: ByteArray,
    ): UploadResult = withContext(Dispatchers.IO) {
        val account = activeAccount()
        val (id, secret) = effectiveCredentials(account)
        if (id.isBlank() || secret.isBlank()) return@withContext UploadResult.NotConfigured
        val token = validAccessToken(account, id, secret) ?: return@withContext UploadResult.NotAuthorized

        val fileBody = tcx.toRequestBody("application/xml".toMediaType())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("name", name)
            .addFormDataPart("description", description)
            .addFormDataPart("data_type", "tcx")
            .addFormDataPart("trainer", "1")
            .addFormDataPart("file", "ride.tcx", fileBody)
            .build()
        val request = Request.Builder()
            .url(StravaConfig.UPLOAD_URL)
            .header("Authorization", "Bearer $token")
            .post(multipart)
            .build()

        val initial = runCatching {
            client.newCall(request).execute().use { resp ->
                val payload = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@use UploadResult.Error("Upload failed (${resp.code}): $payload")
                }
                json.decodeFromString<UploadResponse>(payload)
            }
        }.getOrElse { return@withContext UploadResult.Error(it.message ?: "Network error") }

        if (initial is UploadResult) return@withContext initial
        val upload = initial as UploadResponse
        upload.error?.let { return@withContext UploadResult.Error(it) }

        pollUpload(token, upload.id)
    }

    private suspend fun pollUpload(token: String, uploadId: Long): UploadResult {
        repeat(10) {
            delay(2000)
            val request = Request.Builder()
                .url("${StravaConfig.UPLOAD_URL}/$uploadId")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            val status = runCatching {
                client.newCall(request).execute().use { resp ->
                    json.decodeFromString<UploadResponse>(resp.body?.string().orEmpty())
                }
            }.getOrNull() ?: return UploadResult.Error("Could not check upload status")

            status.error?.let { return UploadResult.Error(it) }
            if (status.activityId != null) return UploadResult.Success(status.activityId)
        }
        return UploadResult.Success(null)
    }

    private suspend fun validAccessToken(account: StravaAccount, clientId: String, clientSecret: String): String? {
        val tokens = StravaTokens(account.accessToken, account.refreshToken, account.expiresAt)
        if (account.refreshToken.isBlank()) return null
        if (!tokens.isExpired() && account.accessToken.isNotBlank()) return account.accessToken
        return refreshAccessToken(account.refreshToken, clientId, clientSecret)
    }

    private suspend fun refreshAccessToken(refreshToken: String, clientId: String, clientSecret: String): String? {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        val request = Request.Builder().url(StravaConfig.TOKEN_URL).post(body).build()
        return runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val tokens = json.decodeFromString<TokenResponse>(resp.body?.string().orEmpty())
                profiles.setActiveStravaTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt)
                tokens.accessToken
            }
        }.getOrNull()
    }
}
