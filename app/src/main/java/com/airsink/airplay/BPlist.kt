package com.airsink.airplay

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Minimal Apple binary property list ("bplist00") codec. Supports what AirPlay uses:
 * dictionaries, arrays, strings, integers, reals, booleans and data.
 */
object BPlist {

    // ---- Encoding ----------------------------------------------------------------------

    fun encode(root: Any): ByteArray {
        val objects = ArrayList<Any>()
        flatten(root, objects)
        val refSize = sizeFor(objects.size.toLong())

        val body = ByteArrayOutputStream()
        body.write("bplist00".toByteArray())
        val offsets = LongArray(objects.size)
        val index = IdentityHashMap(objects)

        for ((i, obj) in objects.withIndex()) {
            offsets[i] = body.size().toLong()
            writeObject(body, obj, refSize, index)
        }

        val offsetTableStart = body.size().toLong()
        val offsetSize = sizeFor(offsetTableStart)
        offsets.forEach { writeSized(body, it, offsetSize) }

        // Trailer: 6 unused bytes, offset int size, ref size, count, root, table offset.
        body.write(ByteArray(6))
        body.write(offsetSize)
        body.write(refSize)
        body.write(ByteBuffer.allocate(8).putLong(objects.size.toLong()).array())
        body.write(ByteBuffer.allocate(8).putLong(0).array())
        body.write(ByteBuffer.allocate(8).putLong(offsetTableStart).array())
        return body.toByteArray()
    }

    private class IdentityHashMap(objects: List<Any>) {
        private val map = java.util.IdentityHashMap<Any, Int>().apply { objects.forEachIndexed { i, o -> put(o, i) } }
        operator fun get(o: Any) = map[o]!!
    }

    /** Collects every object depth-first; each distinct instance gets one slot. */
    private fun flatten(obj: Any, out: MutableList<Any>) {
        out += obj
        when (obj) {
            is Map<*, *> -> {
                obj.keys.forEach { out += it as String }
                obj.values.forEach { flatten(boxed(it!!), out) }
            }
            is List<*> -> obj.forEach { flatten(boxed(it!!), out) }
        }
    }

    // Kotlin may intern small Ints/Strings; wrap them so identity lookups stay unique.
    private class Boxed(val value: Any)
    private fun boxed(v: Any): Any = when (v) {
        is Map<*, *>, is List<*> -> v
        else -> Boxed(v)
    }

    private fun writeObject(out: ByteArrayOutputStream, raw: Any, refSize: Int, index: IdentityHashMap) {
        val obj = if (raw is Boxed) raw.value else raw
        when (obj) {
            is Boolean -> out.write(if (obj) 0x09 else 0x08)
            is Int, is Long -> {
                val v = (obj as Number).toLong()
                when {
                    v in 0..0xFF -> { out.write(0x10); out.write(v.toInt()) }
                    v in 0..0xFFFF -> { out.write(0x11); writeSized(out, v, 2) }
                    v in 0..0xFFFFFFFFL -> { out.write(0x12); writeSized(out, v, 4) }
                    else -> { out.write(0x13); writeSized(out, v, 8) }
                }
            }
            is Double, is Float -> {
                out.write(0x23)
                out.write(ByteBuffer.allocate(8).putDouble((obj as Number).toDouble()).array())
            }
            is ByteArray -> { writeHeader(out, 0x40, obj.size); out.write(obj) }
            is String -> {
                if (obj.all { it.code < 0x80 }) {
                    writeHeader(out, 0x50, obj.length)
                    out.write(obj.toByteArray(Charsets.US_ASCII))
                } else {
                    writeHeader(out, 0x60, obj.length)
                    out.write(obj.toByteArray(Charsets.UTF_16BE))
                }
            }
            is List<*> -> {
                writeHeader(out, 0xA0, obj.size)
                // Children were flattened right after the list, in order.
                var next = index[raw] + 1
                for (child in obj) {
                    writeSized(out, next.toLong(), refSize)
                    next += subtreeSize(child!!)
                }
            }
            is Map<*, *> -> {
                writeHeader(out, 0xD0, obj.size)
                val base = index[raw] + 1
                obj.keys.forEachIndexed { i, _ -> writeSized(out, (base + i).toLong(), refSize) }
                var next = base + obj.size
                for (v in obj.values) {
                    writeSized(out, next.toLong(), refSize)
                    next += subtreeSize(v!!)
                }
            }
            else -> throw IllegalArgumentException("Unsupported plist type: ${obj.javaClass}")
        }
    }

    private fun subtreeSize(obj: Any): Int = when (obj) {
        is Map<*, *> -> 1 + obj.size + obj.values.sumOf { subtreeSize(it!!) }
        is List<*> -> 1 + obj.sumOf { subtreeSize(it!!) }
        else -> 1
    }

    private fun writeHeader(out: ByteArrayOutputStream, marker: Int, count: Int) {
        if (count < 15) {
            out.write(marker or count)
        } else {
            out.write(marker or 0x0F)
            when {
                count <= 0xFF -> { out.write(0x10); out.write(count) }
                count <= 0xFFFF -> { out.write(0x11); writeSized(out, count.toLong(), 2) }
                else -> { out.write(0x12); writeSized(out, count.toLong(), 4) }
            }
        }
    }

    private fun sizeFor(v: Long) = when {
        v <= 0xFF -> 1
        v <= 0xFFFF -> 2
        v <= 0xFFFFFFFFL -> 4
        else -> 8
    }

    private fun writeSized(out: ByteArrayOutputStream, v: Long, size: Int) {
        for (i in size - 1 downTo 0) out.write(((v shr (8 * i)) and 0xFF).toInt())
    }

    // ---- Decoding ----------------------------------------------------------------------

    fun decode(data: ByteArray): Any? {
        require(data.size >= 40 && String(data, 0, 8) == "bplist00") { "Not a binary plist" }
        val t = data.size - 32
        val offsetSize = data[t + 6].toInt()
        val refSize = data[t + 7].toInt()
        val count = readInt(data, t + 8, 8).toInt()
        val root = readInt(data, t + 16, 8).toInt()
        val tableStart = readInt(data, t + 24, 8).toInt()
        val offsets = IntArray(count) { readInt(data, tableStart + it * offsetSize, offsetSize).toInt() }
        return Decoder(data, offsets, refSize).read(root)
    }

    private class Decoder(val d: ByteArray, val offsets: IntArray, val refSize: Int) {
        fun read(ref: Int): Any? {
            var p = offsets[ref]
            val marker = d[p].toInt() and 0xFF
            val type = marker shr 4
            val info = marker and 0x0F
            p++
            fun count(): Int {
                if (info != 0x0F) return info
                val sizeMarker = d[p].toInt() and 0xFF
                val n = 1 shl (sizeMarker and 0x0F)
                val v = readInt(d, p + 1, n).toInt()
                p += 1 + n
                return v
            }
            return when (type) {
                0x0 -> when (info) { 0x08 -> false; 0x09 -> true; else -> null }
                0x1 -> readInt(d, p, 1 shl info)
                0x2 -> if (info == 2) ByteBuffer.wrap(d, p, 4).float.toDouble() else ByteBuffer.wrap(d, p, 8).double
                0x3 -> ByteBuffer.wrap(d, p, 8).double
                0x4 -> { val n = count(); d.copyOfRange(p, p + n) }
                0x5 -> { val n = count(); String(d, p, n, Charsets.US_ASCII) }
                0x6 -> { val n = count(); String(d, p, n * 2, Charsets.UTF_16BE) }
                0x8 -> readInt(d, p, info + 1)
                0xA -> { val n = count(); List(n) { read(readInt(d, p + it * refSize, refSize).toInt()) } }
                0xD -> {
                    val n = count()
                    val keys = List(n) { read(readInt(d, p + it * refSize, refSize).toInt()) as String }
                    val values = List(n) { read(readInt(d, p + (n + it) * refSize, refSize).toInt()) }
                    keys.zip(values).toMap()
                }
                else -> null
            }
        }
    }

    private fun readInt(d: ByteArray, at: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until size) v = (v shl 8) or (d[at + i].toLong() and 0xFF)
        return v
    }
}
