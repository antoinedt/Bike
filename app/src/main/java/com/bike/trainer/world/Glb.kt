package com.bike.trainer.world

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Growable triangle mesh: positions, normals, RGBA colours, two UV sets, indices. */
class MeshData {
    val pos = FloatArr()
    val nrm = FloatArr()
    val col = FloatArr()
    val uv0 = FloatArr()
    val uv1 = FloatArr()
    val idx = IntArr()
    val vertexCount get() = pos.size / 3

    /** Add a vertex (GL/Godot space) and return its index. */
    fun v(x: Double, y: Double, z: Double, nx: Double, ny: Double, nz: Double, rgb: Int, u0: Double = 0.0, v0: Double = 0.0, u1: Double = 0.0, v1: Double = 0.0): Int {
        pos.add(x.toFloat()); pos.add(y.toFloat()); pos.add(z.toFloat())
        nrm.add(nx.toFloat()); nrm.add(ny.toFloat()); nrm.add(nz.toFloat())
        col.add(((rgb shr 16) and 0xFF) / 255f); col.add(((rgb shr 8) and 0xFF) / 255f); col.add((rgb and 0xFF) / 255f); col.add(1f)
        uv0.add(u0.toFloat()); uv0.add(v0.toFloat())
        uv1.add(u1.toFloat()); uv1.add(v1.toFloat())
        return vertexCount - 1
    }

    fun tri(a: Int, b: Int, c: Int) { idx.add(a); idx.add(b); idx.add(c) }

    fun isEmpty() = idx.size == 0
}

class FloatArr {
    var a = FloatArray(1024); var size = 0
    fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
}

class IntArr {
    var a = IntArray(1024); var size = 0
    fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
}

/**
 * Writes a minimal glTF 2.0 binary: one node per named mesh, each with one
 * primitive and a named material (optionally textured with an embedded PNG).
 * Godot loads it at runtime (GLTFDocument) and swaps the materials for its toon
 * shaders by name.
 */
class GlbWriter {
    private class Item(val name: String, val mesh: MeshData, val material: String, val texturePng: ByteArray?)
    private val items = ArrayList<Item>()

    fun add(name: String, mesh: MeshData, material: String, texturePng: ByteArray? = null) {
        if (!mesh.isEmpty()) items.add(Item(name, mesh, material, texturePng))
    }

    fun isEmpty() = items.isEmpty()

    fun bytes(): ByteArray {
        val bin = ByteArrayOutputStream()
        val views = StringBuilder()
        val accessors = StringBuilder()
        val meshes = StringBuilder()
        val nodes = StringBuilder()
        val materials = StringBuilder()
        val textures = StringBuilder()
        val images = StringBuilder()
        var viewCount = 0
        var accCount = 0
        var texCount = 0

        fun align() { while (bin.size() % 4 != 0) bin.write(0) }
        fun view(bytes: ByteArray, target: Int?): Int {
            align()
            val off = bin.size()
            bin.write(bytes)
            if (viewCount > 0) views.append(',')
            views.append("{\"buffer\":0,\"byteOffset\":$off,\"byteLength\":${bytes.size}")
            if (target != null) views.append(",\"target\":$target")
            views.append('}')
            return viewCount++
        }
        fun floats(a: FloatArray, n: Int): ByteArray {
            val bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) bb.putFloat(a[i])
            return bb.array()
        }
        fun accessor(view: Int, comp: Int, count: Int, type: String, minMax: String = ""): Int {
            if (accCount > 0) accessors.append(',')
            accessors.append("{\"bufferView\":$view,\"componentType\":$comp,\"count\":$count,\"type\":\"$type\"$minMax}")
            return accCount++
        }

        items.forEachIndexed { i, it ->
            val m = it.mesh
            val n = m.vertexCount
            var mnx = Float.MAX_VALUE; var mny = Float.MAX_VALUE; var mnz = Float.MAX_VALUE
            var mxx = -Float.MAX_VALUE; var mxy = -Float.MAX_VALUE; var mxz = -Float.MAX_VALUE
            for (k in 0 until n) {
                val x = m.pos.a[3 * k]; val y = m.pos.a[3 * k + 1]; val z = m.pos.a[3 * k + 2]
                mnx = minOf(mnx, x); mny = minOf(mny, y); mnz = minOf(mnz, z)
                mxx = maxOf(mxx, x); mxy = maxOf(mxy, y); mxz = maxOf(mxz, z)
            }
            val aPos = accessor(view(floats(m.pos.a, n * 3), 34962), 5126, n, "VEC3",
                ",\"min\":[$mnx,$mny,$mnz],\"max\":[$mxx,$mxy,$mxz]")
            val aNrm = accessor(view(floats(m.nrm.a, n * 3), 34962), 5126, n, "VEC3")
            val aCol = accessor(view(floats(m.col.a, n * 4), 34962), 5126, n, "VEC4")
            val aUv0 = accessor(view(floats(m.uv0.a, n * 2), 34962), 5126, n, "VEC2")
            val aUv1 = accessor(view(floats(m.uv1.a, n * 2), 34962), 5126, n, "VEC2")
            val ib = ByteBuffer.allocate(m.idx.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until m.idx.size) ib.putInt(m.idx.a[k])
            val aIdx = accessor(view(ib.array(), 34963), 5125, m.idx.size, "SCALAR")

            var texRef = ""
            if (it.texturePng != null) {
                val iv = view(it.texturePng, null)
                if (texCount > 0) { images.append(','); textures.append(',') }
                images.append("{\"bufferView\":$iv,\"mimeType\":\"image/png\"}")
                textures.append("{\"source\":$texCount,\"sampler\":0}")
                texRef = ",\"baseColorTexture\":{\"index\":$texCount}"
                texCount++
            }
            if (i > 0) { materials.append(','); meshes.append(','); nodes.append(',') }
            materials.append("{\"name\":\"${it.material}\",\"pbrMetallicRoughness\":{\"metallicFactor\":0,\"roughnessFactor\":1$texRef}}")
            meshes.append("{\"name\":\"${it.name}\",\"primitives\":[{\"attributes\":{\"POSITION\":$aPos,\"NORMAL\":$aNrm,\"COLOR_0\":$aCol,\"TEXCOORD_0\":$aUv0,\"TEXCOORD_1\":$aUv1},\"indices\":$aIdx,\"material\":$i}]}")
            nodes.append("{\"name\":\"${it.name}\",\"mesh\":$i}")
        }
        align()
        val nodeList = items.indices.joinToString(",")
        val json = StringBuilder("{\"asset\":{\"version\":\"2.0\",\"generator\":\"VibeBike world builder\"},")
        json.append("\"scene\":0,\"scenes\":[{\"nodes\":[$nodeList]}],")
        json.append("\"nodes\":[$nodes],\"meshes\":[$meshes],\"materials\":[$materials],")
        if (texCount > 0) {
            json.append("\"textures\":[$textures],\"images\":[$images],")
            json.append("\"samplers\":[{\"magFilter\":9729,\"minFilter\":9987,\"wrapS\":33071,\"wrapT\":33071}],")
        }
        json.append("\"accessors\":[$accessors],\"bufferViews\":[$views],")
        json.append("\"buffers\":[{\"byteLength\":${bin.size()}}]}")
        var jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        val pad = (4 - jsonBytes.size % 4) % 4
        jsonBytes += ByteArray(pad) { ' '.code.toByte() }
        val binBytes = bin.toByteArray()
        val total = 12 + 8 + jsonBytes.size + 8 + binBytes.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67); out.putInt(2); out.putInt(total)
        out.putInt(jsonBytes.size); out.putInt(0x4E4F534A); out.put(jsonBytes)
        out.putInt(binBytes.size); out.putInt(0x004E4942); out.put(binBytes)
        return out.array()
    }
}
