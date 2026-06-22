package com.bike.trainer.strava

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
 * Persists Strava OAuth tokens and performs the token exchange/refresh plus the
 * activity upload. All network calls run on [Dispatchers.IO].
 */
class StravaRepository(private val dataStore: DataStore<Preferences>) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    val isConnected: Flow<Boolean> =
        dataStore.data.map { !it[REFRESH_TOKEN].isNullOrBlank() }

    suspend fun isConfigured(): Boolean = StravaConfig.isConfigured

    /** Exchange an authorization code (from the OAuth redirect) for tokens. */
    suspend fun exchangeAuthorizationCode(code: String): Boolean = withContext(Dispatchers.IO) {
        if (!StravaConfig.isConfigured) return@withContext false
        val body = FormBody.Builder()
            .add("client_id", StravaConfig.clientId)
            .add("client_secret", StravaConfig.clientSecret)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .build()
        val request = Request.Builder().url(StravaConfig.TOKEN_URL).post(body).build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val tokens = json.decodeFromString<TokenResponse>(resp.body?.string().orEmpty())
                persist(tokens)
                true
            }
        }.getOrDefault(false)
    }

    suspend fun disconnect() {
        dataStore.edit {
            it.remove(ACCESS_TOKEN)
            it.remove(REFRESH_TOKEN)
            it.remove(EXPIRES_AT)
        }
    }

    /**
     * Upload a TCX file as a new Strava activity. Refreshes the token first if
     * needed, then polls the upload until Strava finishes processing it.
     */
    suspend fun uploadTcx(
        name: String,
        description: String,
        tcx: ByteArray,
    ): UploadResult = withContext(Dispatchers.IO) {
        if (!StravaConfig.isConfigured) return@withContext UploadResult.NotConfigured
        val token = validAccessToken() ?: return@withContext UploadResult.NotAuthorized

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
        // Strava accepted it but is still processing; treat as success.
        return UploadResult.Success(null)
    }

    private suspend fun validAccessToken(): String? {
        val prefs = dataStore.data.first()
        val access = prefs[ACCESS_TOKEN]
        val refresh = prefs[REFRESH_TOKEN] ?: return null
        val expiresAt = prefs[EXPIRES_AT] ?: 0L
        val tokens = StravaTokens(access.orEmpty(), refresh, expiresAt)
        if (!tokens.isExpired() && access != null) return access
        return refreshAccessToken(refresh)
    }

    private fun refreshAccessToken(refreshToken: String): String? {
        val body = FormBody.Builder()
            .add("client_id", StravaConfig.clientId)
            .add("client_secret", StravaConfig.clientSecret)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        val request = Request.Builder().url(StravaConfig.TOKEN_URL).post(body).build()
        return runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val tokens = json.decodeFromString<TokenResponse>(resp.body?.string().orEmpty())
                persistBlocking(tokens)
                tokens.accessToken
            }
        }.getOrNull()
    }

    private suspend fun persist(tokens: TokenResponse) {
        dataStore.edit {
            it[ACCESS_TOKEN] = tokens.accessToken
            it[REFRESH_TOKEN] = tokens.refreshToken
            it[EXPIRES_AT] = tokens.expiresAt
        }
    }

    // Used from the synchronous refresh path.
    private fun persistBlocking(tokens: TokenResponse) {
        kotlinx.coroutines.runBlocking { persist(tokens) }
    }

    private companion object {
        val ACCESS_TOKEN = stringPreferencesKey("strava_access_token")
        val REFRESH_TOKEN = stringPreferencesKey("strava_refresh_token")
        val EXPIRES_AT = longPreferencesKey("strava_expires_at")
    }
}
