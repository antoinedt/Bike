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
    /** Fingerprint of the route the frames were fetched for; guards against stale reuse. */
    val routeFingerprint: String = "",
) {
    val imageCount: Int get() = samples.count { it.file != null }
}

/**
 * On-disk cache of prefetched Street View Static images, keyed by a route id (the
 * GPX file name). Lives in the app's external files dir (next to the routes
 * folder) so it's reachable over USB / a file manager and can be backed up.
 */
object StreetViewCache {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private fun baseDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "svcache").apply { if (!exists()) mkdirs() }
    }

    /** Root folder holding every route's cached frames (for backup/restore). */
    fun cacheRoot(context: Context): File = baseDir(context)

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

    /**
     * Load a cache for [route] only if it's valid: the manifest's fingerprint must
     * match the route (so a changed GPX with the same name isn't reused), and at
     * least one referenced image must still be on disk. Returns null otherwise.
     */
    fun loadFor(context: Context, route: Route): StreetViewManifest? {
        val manifest = load(context, route.id) ?: return null
        // Legacy manifests (no fingerprint) are trusted; new ones must match.
        if (manifest.routeFingerprint.isNotEmpty() &&
            manifest.routeFingerprint != fingerprint(route)
        ) return null
        val dir = routeDir(context, manifest.routeId)
        val anyPresent = manifest.samples.any { it.file != null && File(dir, it.file).exists() }
        return if (anyPresent) manifest else null
    }

    /** Stable content fingerprint for a route (point count, length, endpoints). */
    fun fingerprint(route: Route): String {
        val pts = route.points
        if (pts.isEmpty()) return "empty"
        val a = pts.first()
        val b = pts.last()
        return buildString {
            append(pts.size); append('|')
            append(route.totalDistance.toInt()); append('|')
            append(String.format(java.util.Locale.US, "%.5f,%.5f", a.lat, a.lon)); append('|')
            append(String.format(java.util.Locale.US, "%.5f,%.5f", b.lat, b.lon))
        }
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

    /** The cached image file nearest to [distance] that is present on disk, or null. */
    fun imageFileAt(context: Context, manifest: StreetViewManifest, distance: Double): File? {
        val dir = routeDir(context, manifest.routeId)
        var best: File? = null
        var bestDelta = Double.MAX_VALUE
        for (s in manifest.samples) {
            val name = s.file ?: continue
            val file = File(dir, name)
            if (!file.exists()) continue
            val d = abs(s.distance - distance)
            if (d < bestDelta) {
                bestDelta = d
                best = file
            }
        }
        return best
    }

    fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]+"), "_")
}
