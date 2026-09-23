package com.airsink.airplay

/**
 * Wraps 16-bit stereo PCM in "uncompressed" ALAC frames. ALAC allows verbatim frames, so no real
 * compression is needed for LAN streaming; every AirPlay receiver can decode these.
 */
object Alac {
    const val FRAMES_PER_PACKET = 352

    /** [pcm] is interleaved little-endian 16-bit stereo, exactly [frames] frames long. */
    fun encode(pcm: ByteArray, offset: Int, frames: Int): ByteArray {
        val w = BitWriter(frames * 4 + 16)
        w.write(1, 3)          // element: channel pair
        w.write(0, 4)          // element instance tag
        w.write(0, 12)         // unused
        val partial = frames != FRAMES_PER_PACKET
        w.write(if (partial) 1 else 0, 1) // explicit sample count follows
        w.write(0, 2)          // uncompressed bytes shift
        w.write(1, 1)          // verbatim (not compressed)
        if (partial) w.write(frames, 32)
        for (i in 0 until frames * 2) {
            val lo = pcm[offset + i * 2].toInt() and 0xFF
            val hi = pcm[offset + i * 2 + 1].toInt() and 0xFF
            w.write((hi shl 8) or lo, 16)
        }
        w.write(7, 3)          // end tag
        return w.toByteArray()
    }

    private class BitWriter(capacity: Int) {
        private val buf = ByteArray(capacity)
        private var bitPos = 0

        fun write(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                if ((value shr i) and 1 == 1) {
                    val byteIndex = bitPos ushr 3
                    buf[byteIndex] = (buf[byteIndex].toInt() or (0x80 ushr (bitPos and 7))).toByte()
                }
                bitPos++
            }
        }

        fun toByteArray(): ByteArray = buf.copyOf((bitPos + 7) ushr 3)
    }
}
