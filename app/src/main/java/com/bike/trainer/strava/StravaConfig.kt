package com.bike.trainer.strava

import java.net.URLEncoder

/** Endpoints and OAuth parameters for the Strava v3 API. */
object StravaConfig {
    const val AUTHORIZE_URL = "https://www.strava.com/oauth/mobile/authorize"
    const val TOKEN_URL = "https://www.strava.com/oauth/token"
    const val UPLOAD_URL = "https://www.strava.com/api/v3/uploads"

    // Must match the intent-filter in AndroidManifest.xml (bike://strava-auth).
    const val REDIRECT_URI = "bike://strava-auth"
    const val SCOPE = "activity:write,read"

    /** Build the authorization URL for a given (runtime-resolved) client id. */
    fun authorizeUrl(clientId: String): String =
        "$AUTHORIZE_URL?client_id=${enc(clientId)}" +
            "&redirect_uri=${enc(REDIRECT_URI)}" +
            "&response_type=code" +
            "&approval_prompt=auto" +
            "&scope=${enc(SCOPE)}"

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")
}
