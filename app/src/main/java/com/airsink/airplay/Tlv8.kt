package com.airsink.airplay

import java.io.ByteArrayOutputStream

/** HomeKit TLV8: type, length, value, with values over 255 bytes split into fragments. */
object Tlv8 {
    const val METHOD = 0x00
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val STATE = 0x06
    const val ERROR = 0x07
    const val FLAGS = 0x13

    fun encode(vararg items: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in items) {
            var offset = 0
            do {
                val len = minOf(255, value.size - offset)
                out.write(type)
                out.write(len)
                out.write(value, offset, len)
                offset += len
            } while (offset < value.size)
        }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArray>()
        var i = 0
        var lastType = -1
        while (i + 2 <= data.size) {
            val type = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            val value = data.copyOfRange(i + 2, minOf(data.size, i + 2 + len))
            // Consecutive items of the same type are fragments of one value.
            result[type] = if (type == lastType) result[type]!! + value else value
            lastType = type
            i += 2 + len
        }
        return result
    }

    fun byte(v: Int) = byteArrayOf(v.toByte())
}
