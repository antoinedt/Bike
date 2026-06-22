package com.bike.trainer.strava

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OAuth token response from /oauth/token. */
@Serializable
data class TokenResponse(
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_at") val expiresAt: Long = 0L,
)

/** Response from POST /uploads and GET /uploads/{id}. */
@Serializable
data class UploadResponse(
    val id: Long = 0L,
    @SerialName("id_str") val idStr: String? = null,
    @SerialName("external_id") val externalId: String? = null,
    val error: String? = null,
    val status: String? = null,
    @SerialName("activity_id") val activityId: Long? = null,
)

/** Persisted Strava credentials. */
data class StravaTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
) {
    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        nowSeconds >= (expiresAt - 60) // refresh a minute early
}

/** Result of an upload attempt surfaced to the UI. */
sealed interface UploadResult {
    data class Success(val activityId: Long?) : UploadResult
    data class Error(val message: String) : UploadResult
    data object NotAuthorized : UploadResult
    data object NotConfigured : UploadResult
}
