package com.bike.trainer.garmin

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Stores the long-lived Garmin OAuth1 token and drives login (incl. MFA) + upload
 * through [GarminClient]. The token is good for ~1 year; the short-lived OAuth2
 * bearer is fetched fresh on each upload.
 */
class GarminRepository(private val dataStore: DataStore<Preferences>) {

    val isConnected: Flow<Boolean> = dataStore.data.map { it[TOKEN]?.isNotBlank() == true }

    // A client kept alive across an MFA challenge (shares the sign-in session).
    private var pendingClient: GarminClient? = null

    suspend fun beginLogin(email: String, password: String): GarminLoginResult {
        val client = GarminClient()
        val result = client.login(email.trim(), password)
        when (result) {
            is GarminLoginResult.Success -> save(result.oauth1)
            is GarminLoginResult.MfaRequired -> pendingClient = client
            is GarminLoginResult.Error -> Unit
        }
        return result
    }

    suspend fun submitMfa(code: String): GarminLoginResult {
        val client = pendingClient ?: return GarminLoginResult.Error("No login in progress")
        val result = client.resumeMfa(code)
        if (result is GarminLoginResult.Success) {
            save(result.oauth1)
            pendingClient = null
        }
        return result
    }

    suspend fun disconnect() {
        pendingClient = null
        dataStore.edit { it.remove(TOKEN); it.remove(SECRET) }
    }

    suspend fun upload(fileName: String, bytes: ByteArray): GarminUploadResult {
        val prefs = dataStore.data.first()
        val token = prefs[TOKEN]; val secret = prefs[SECRET]
        if (token.isNullOrBlank() || secret.isNullOrBlank()) return GarminUploadResult.NotConnected
        return GarminClient().upload(GarminOAuth1(token, secret), fileName, bytes)
    }

    private suspend fun save(oauth1: GarminOAuth1) {
        dataStore.edit { it[TOKEN] = oauth1.token; it[SECRET] = oauth1.tokenSecret }
    }

    private companion object {
        val TOKEN = stringPreferencesKey("garmin_oauth_token")
        val SECRET = stringPreferencesKey("garmin_oauth_secret")
    }
}
