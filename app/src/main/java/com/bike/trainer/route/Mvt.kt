package com.bike.trainer.route

/**
 * Minimal Mapbox Vector Tile (MVT v2) decoder: just enough protobuf to read the
 * layers, feature tags and geometry of the OpenMapTiles tiles used by the stylized
 * scenery. Geometry stays in tile units (0..extent, y pointing south).
 */
object Mvt {
    const val POINT = 1
    const val LINE = 2
    const val POLYGON = 3

    class Feature(
        val layer: String,
        val type: Int,
        val props: Map<String, Any>,
        /** Rings / lines as interleaved x,y tile coordinates. */
        val parts: List<FloatArray>,
        val extent: Int,
    ) {
        fun str(key: String): String? = props[key] as? String
        fun num(key: String): Double? = (props[key] as? Number)?.toDouble()
    }

    /** Decode the features of the named [layers] (all layers when null). */
    fun decode(bytes: ByteArray, layers: Set<String>? = null): List<Feature> {
        val out = ArrayList<Feature>()
        val r = Reader(bytes, 0, bytes.size)
        while (r.more()) {
            val tag = r.varint().toInt()
            if (tag ushr 3 == 3 && tag and 7 == 2) {
                val len = r.varint().toInt()
                decodeLayer(Reader(bytes, r.pos, r.pos + len), layers, out)
                r.pos += len
            } else {
                r.skip(tag and 7)
            }
        }
        return out
    }

    private fun decodeLayer(r: Reader, wanted: Set<String>?, out: MutableList<Feature>) {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val featureRanges = ArrayList<IntArray>()
        while (r.more()) {
            val tag = r.varint().toInt()
            when (tag ushr 3) {
                1 -> name = r.string()
                2 -> {
                    val len = r.varint().toInt()
                    featureRanges.add(intArrayOf(r.pos, r.pos + len))
                    r.pos += len
                }
                3 -> keys.add(r.string())
                4 -> {
                    val len = r.varint().toInt()
                    values.add(decodeValue(Reader(r.buf, r.pos, r.pos + len)))
                    r.pos += len
                }
                5 -> extent = r.varint().toInt()
                else -> r.skip(tag and 7)
            }
        }
        if (wanted != null && name !in wanted) return
        for ((start, end) in featureRanges.map { it[0] to it[1] }) {
            decodeFeature(Reader(r.buf, start, end), name, extent, keys, values)?.let { out.add(it) }
        }
    }

    private fun decodeValue(r: Reader): Any {
        var v: Any = ""
        while (r.more()) {
            val tag = r.varint().toInt()
            when (tag ushr 3) {
                1 -> v = r.string()
                2 -> v = java.lang.Float.intBitsToFloat(r.fixed32())
                3 -> v = java.lang.Double.longBitsToDouble(r.fixed64())
                4, 5 -> v = r.varint()
                6 -> { val z = r.varint(); v = (z ushr 1) xor -(z and 1) }
                7 -> v = r.varint() != 0L
                else -> r.skip(tag and 7)
            }
        }
        return v
    }

    private fun decodeFeature(
        r: Reader, layer: String, extent: Int, keys: List<String>, values: List<Any>,
    ): Feature? {
        var type = 0
        var tags: IntArray? = null
        var geom: IntArray? = null
        while (r.more()) {
            val tag = r.varint().toInt()
            when (tag ushr 3) {
                2 -> tags = r.packed()
                3 -> type = r.varint().toInt()
                4 -> geom = r.packed()
                else -> r.skip(tag and 7)
            }
        }
        val g = geom ?: return null
        val props = HashMap<String, Any>()
        tags?.let { t ->
            var i = 0
            while (i + 1 < t.size) {
                val k = keys.getOrNull(t[i]); val v = values.getOrNull(t[i + 1])
                if (k != null && v != null) props[k] = v
                i += 2
            }
        }
        return Feature(layer, type, props, geometry(g), extent)
    }

    private fun geometry(g: IntArray): List<FloatArray> {
        val parts = ArrayList<FloatArray>()
        var cur = FloatArrayBuilder()
        var x = 0; var y = 0
        var i = 0
        while (i < g.size) {
            val cmd = g[i] and 7
            val count = g[i] ushr 3
            i++
            when (cmd) {
                1, 2 -> repeat(count) {
                    if (i + 1 >= g.size) return parts.also { p -> if (cur.size >= 4) p.add(cur.build()) }
                    x += zigzag(g[i]); y += zigzag(g[i + 1]); i += 2
                    if (cmd == 1 && cur.size > 0) { if (cur.size >= 4) parts.add(cur.build()); cur = FloatArrayBuilder() }
                    cur.add(x.toFloat(), y.toFloat())
                }
                7 -> { if (cur.size >= 6) parts.add(cur.build()); cur = FloatArrayBuilder() }
                else -> return parts
            }
        }
        if (cur.size >= 4) parts.add(cur.build())
        return parts
    }

    private fun zigzag(n: Int) = (n ushr 1) xor -(n and 1)

    private class FloatArrayBuilder {
        private var a = FloatArray(16)
        var size = 0
        fun add(x: Float, y: Float) {
            if (size + 2 > a.size) a = a.copyOf(a.size * 2)
            a[size++] = x; a[size++] = y
        }
        fun build() = a.copyOf(size)
    }

    private class Reader(val buf: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (pos < end) {
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return result
        }
        fun fixed32(): Int {
            var v = 0
            for (i in 0..3) v = v or ((buf[pos + i].toInt() and 0xFF) shl (8 * i))
            pos += 4
            return v
        }
        fun fixed64(): Long {
            var v = 0L
            for (i in 0..7) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 8
            return v
        }
        fun string(): String {
            val len = varint().toInt()
            val s = String(buf, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }
        fun packed(): IntArray {
            val len = varint().toInt()
            val stop = pos + len
            val out = ArrayList<Int>()
            while (pos < stop) out.add(varint().toInt())
            pos = stop
            return out.toIntArray()
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += varint().toInt()
                5 -> pos += 4
                else -> pos = end
            }
        }
    }
}
