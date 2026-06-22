package com.bike.trainer.route

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs

/** One sampled point of a prefetched route, with its cached image (or null = no coverage). */
@Serializable
data class StreetViewSample(
    val distance: Double,
    val file: String?,
)

/** Manifest describing a route's prefetched Street View frames. */
@Serializable
data class StreetViewManifest(
    val routeId: String,
    val spacingMeters: Double,
    val totalDistance: Double,
    val samples: List<StreetViewSample>,
) {
    val imageCount: Int get() = samples.count { it.file != null }
}

/**
 * On-disk cache of prefetched Street View Static images, keyed by a route id (the
 * GPX file name). Lives in internal storage so it's private to the app.
 */
object StreetViewCache {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private fun baseDir(context: Context): File =
        File(context.filesDir, "svcache").apply { if (!exists()) mkdirs() }

    fun routeDir(context: Context, routeId: String): File =
        File(baseDir(context), sanitize(routeId)).apply { if (!exists()) mkdirs() }

    private fun manifestFile(context: Context, routeId: String): File =
        File(routeDir(context, routeId), "manifest.json")

    fun load(context: Context, routeId: String?): StreetViewManifest? {
        if (routeId == null) return null
        val f = manifestFile(context, routeId)
        if (!f.exists()) return null
        return runCatching {
            json.decodeFromString(StreetViewManifest.serializer(), f.readText())
        }.getOrNull()
    }

    fun save(context: Context, manifest: StreetViewManifest) {
        manifestFile(context, manifest.routeId)
            .writeText(json.encodeToString(StreetViewManifest.serializer(), manifest))
    }

    fun hasCache(context: Context, routeId: String?): Boolean =
        routeId != null && manifestFile(context, routeId).exists()

    fun delete(context: Context, routeId: String) {
        routeDir(context, routeId).deleteRecursively()
    }

    /** The cached image file nearest to [distance] that has coverage, or null. */
    fun imageFileAt(context: Context, manifest: StreetViewManifest, distance: Double): File? {
        var best: StreetViewSample? = null
        var bestDelta = Double.MAX_VALUE
        for (s in manifest.samples) {
            val name = s.file ?: continue
            val d = abs(s.distance - distance)
            if (d < bestDelta) {
                bestDelta = d
                best = s
            }
        }
        val name = best?.file ?: return null
        return File(routeDir(context, manifest.routeId), name)
    }

    fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]+"), "_")
}
