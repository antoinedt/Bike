package com.bike.trainer.strava

import com.bike.trainer.BuildConfig

/** Endpoints and OAuth parameters for the Strava v3 API. */
object StravaConfig {
    const val AUTHORIZE_URL = "https://www.strava.com/oauth/mobile/authorize"
    const val TOKEN_URL = "https://www.strava.com/oauth/token"
    const val UPLOAD_URL = "https://www.strava.com/api/v3/uploads"

    // Must match the intent-filter in AndroidManifest.xml (bike://strava-auth).
    const val REDIRECT_URI = "bike://strava-auth"
    const val SCOPE = "activity:write,read"

    val clientId: String get() = BuildConfig.STRAVA_CLIENT_ID
    val clientSecret: String get() = BuildConfig.STRAVA_CLIENT_SECRET

    /** True once real credentials have been supplied at build time. */
    val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    fun authorizeUrl(): String =
        "$AUTHORIZE_URL?client_id=$clientId" +
            "&redirect_uri=$REDIRECT_URI" +
            "&response_type=code" +
            "&approval_prompt=auto" +
            "&scope=$SCOPE"
}
