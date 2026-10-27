package com.bike.trainer.ui.world

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.bike.trainer.route.StreetViewCache
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The user's own 3D asset packs for the 3D World: glTF models named after the prop
 * slots (round_tree.glb, chest.glb …, see [SLOTS]), an optional pack.json mapping
 * slots to models with a size, and an optional atlas.png for the themes' tiles.
 *
 * Packs are imported from a .zip (any document provider: storage, Google Drive …)
 * into the app's private storage; they never leave the phone.
 */
object AssetPacks {
    /** Prop slots a pack can fill, per world (keys as the world builders place them). */
    val SLOTS = linkedMapOf(
        "Kingdom Quest" to listOf("round_tree", "conifer", "bush", "rock", "boulder", "mushroom", "pot", "chest", "gem", "heart", "sign", "lily"),
        "Robot City" to listOf("bot", "capsule"),
        "Real place" to listOf(
            "maple", "linden", "ash", "locust", "elm", "oak", "poplar", "birch", "small_tree", "conifer", "tree",
            "car", "lamp", "bench", "bus_stop", "signal", "bollard", "bike_rack", "bin", "hydrant",
        ),
    )

    class Pack(val id: String, val name: String, val slots: List<String>, val hasAtlas: Boolean)

    private fun root(context: Context) = File(context.filesDir, "assetpacks")

    fun dir(context: Context, id: String) = File(root(context), id)

    fun list(context: Context): List<Pack> =
        root(context).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.map { describe(it) } ?: emptyList()

    private fun describe(d: File): Pack {
        val cfg = File(d, "pack.json").takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        val slots = LinkedHashSet<String>()
        d.listFiles()?.forEach { f -> if (f.extension.lowercase() in setOf("glb", "gltf")) slots.add(f.nameWithoutExtension) }
        cfg?.optJSONObject("props")?.keys()?.forEach { slots.add(it) }
        val atlas = d.listFiles()?.any { it.name == "atlas.png" || it.name.endsWith("_atlas.png") } == true
        return Pack(d.name, cfg?.optString("name")?.takeIf { it.isNotBlank() } ?: d.name, slots.toList(), atlas)
    }

    fun delete(context: Context, id: String) = dir(context, id).deleteRecursively()

    /**
     * Import a pack from a .zip. A single top-level folder in the zip is unwrapped.
     * Returns the pack, or throws with a readable message.
     */
    fun import(context: Context, uri: Uri): Pack {
        val display = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "pack.zip"
        val tmp = File(root(context), ".import").apply { deleteRecursively(); mkdirs() }
        var total = 0L
        val input = context.contentResolver.openInputStream(uri) ?: error("Can't open $display")
        ZipInputStream(input.buffered()).use { zip ->
            val base = tmp.canonicalPath + File.separator
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(tmp, e.name)
                // No escaping the pack folder ("zip slip").
                if (!out.canonicalPath.startsWith(base)) error("Bad path in zip: ${e.name}")
                if (e.isDirectory) { out.mkdirs(); continue }
                if (e.name.startsWith("__MACOSX/")) continue
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) error("Pack too big (over ${MAX_BYTES / 1_000_000} MB)")
                        o.write(buf, 0, n)
                    }
                }
            }
        }
        // Unwrap a single top folder.
        var top = tmp
        while (true) {
            val kids = top.listFiles()?.filter { it.name != "__MACOSX" } ?: break
            if (kids.size == 1 && kids[0].isDirectory) top = kids[0] else break
        }
        val found = describe(top)
        if (found.slots.isEmpty() && !found.hasAtlas) {
            tmp.deleteRecursively()
            error("No models (.glb / .gltf named after a slot, or a pack.json) and no atlas.png in $display")
        }
        val id = StreetViewCache.sanitize(display.removeSuffix(".zip").removeSuffix(".ZIP")).ifBlank { "pack" }
        val dest = dir(context, id)
        dest.deleteRecursively()
        if (!top.renameTo(dest)) { top.copyRecursively(dest, overwrite = true) }
        tmp.deleteRecursively()
        return describe(dest)
    }

    private const val MAX_BYTES = 400_000_000L
}
