package com.medialibrary.manager.tv

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.regex.Pattern

/**
 * Widevine licence acquisition for Fizz's live streams, done exactly as the Fizz app does it (the
 * Vualto "vudrm" scheme): the key request is wrapped in a small JSON envelope — the account's
 * stream token, the raw Widevine request bytes, and the content's key id (read from the DASH
 * manifest's `default_kid`) — and POSTed to the Vualto licence proxy. No keys are handled here;
 * they stay inside the platform's Widevine module.
 */
@UnstableApi
class FizzWidevineCallback(
    private val licenseUrl: String,
    private val token: String,
    private val manifestUrl: String
) : MediaDrmCallback {

    @Volatile private var cachedKid: String? = null

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
        val payload = JSONObject()
        payload.put("token", token)
        val drmInfo = JSONArray()
        for (b in request.data) drmInfo.put(b.toInt() and 0xFF)
        payload.put("drm_info", drmInfo)
        payload.put("kid", kid())
        return post(licenseUrl, payload.toString().toByteArray(), "application/json", "vudrm-widevine OmniPlay")
    }

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray {
        val url = request.defaultUrl + "&signedRequest=" + String(request.data)
        return post(url, ByteArray(0), null, null)
    }

    /** The content key id, read once from the DASH manifest's `default_kid` (empty if not found). */
    private fun kid(): String {
        cachedKid?.let { return it }
        val manifest = runCatching { String(post(manifestUrl, null, null, null)) }.getOrDefault("")
        val matcher = Pattern.compile("default_kid=\"(.*?)\"", Pattern.CASE_INSENSITIVE).matcher(manifest)
        val value = if (matcher.find()) matcher.group(1) ?: "" else ""
        cachedKid = value
        return value
    }

    private fun post(url: String, body: ByteArray?, contentType: String?, vuplayVersion: String?): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                if (vuplayVersion != null) conn.setRequestProperty("vuplay-version", vuplayVersion)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code != 200) {
                val transaction = conn.getHeaderField("vualto-transaction-id")
                throw IOException("Licence server returned $code" + (transaction?.let { " (transaction $it)" } ?: ""))
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        val WIDEVINE_UUID: UUID = C.WIDEVINE_UUID
    }
}
