package com.bike.trainer.route

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Quick check of whether Google Street View actually covers a point, via the free
 * metadata endpoint. Used to decide if a route should play the live panorama or
 * fall back to the map — the Street View SDK can crash when driven to coordinates
 * with no coverage, so we never create it for an uncovered route.
 */
object StreetViewCoverage {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * True when official (outdoor) Street View imagery exists near [lat],[lon].
     * Returns false on any error or no network — the safe default is the map.
     */
    suspend fun hasCoverage(lat: Double, lon: Double, key: String): Boolean =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) return@withContext false
            runCatching {
                val url = "https://maps.googleapis.com/maps/api/streetview/metadata" +
                    "?location=$lat,$lon&source=outdoor&key=$key"
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val body = resp.body?.string() ?: return@use false
                    JSONObject(body).optString("status") == "OK"
                }
            }.getOrDefault(false)
        }
}
