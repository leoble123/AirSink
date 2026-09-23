package com.airsink.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataOutputStream
import java.io.File

class Tlv8AlacTest {

    @Test
    fun tlvFragmentsLongValues() {
        val key = ByteArray(384) { it.toByte() }
        val encoded = Tlv8.encode(Tlv8.STATE to Tlv8.byte(3), Tlv8.PUBLIC_KEY to key)
        // 3 bytes for state, then 255 + 129 split into two fragments with 2-byte headers each.
        assertEquals(3 + 2 + 255 + 2 + 129, encoded.size)
        val decoded = Tlv8.decode(encoded)
        assertArrayEquals(key, decoded[Tlv8.PUBLIC_KEY])
        assertArrayEquals(byteArrayOf(3), decoded[Tlv8.STATE])
    }

    /** Test tone used by both this test and the FFmpeg cross-check script. */
    private fun pcm(frames: Int, offset: Int): ByteArray {
        val out = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val n = offset + i
            val l = (Math.sin(n * 2 * Math.PI * 440 / 44100) * 12000).toInt()
            val r = (Math.sin(n * 2 * Math.PI * 660 / 44100) * 9000).toInt()
            out[i * 4] = l.toByte(); out[i * 4 + 1] = (l shr 8).toByte()
            out[i * 4 + 2] = r.toByte(); out[i * 4 + 3] = (r shr 8).toByte()
        }
        return out
    }

    @Test
    fun alacFramesHaveExpectedSize() {
        val frame = Alac.encode(pcm(352, 0), 0, 352)
        // 3+4+12+1+2+1 header bits, 352*2*16 sample bits, 3 end bits → 22556 bits.
        assertEquals((23 + 352 * 32 + 3 + 7) / 8, frame.size)

        // Dump a few frames so scripts/verify_alac.py can decode them with FFmpeg.
        val dir = File(System.getProperty("java.io.tmpdir"), "airsink-alac").apply { mkdirs() }
        DataOutputStream(File(dir, "frames.bin").outputStream()).use { out ->
            for (p in 0 until 50) {
                val f = Alac.encode(pcm(352, p * 352), 0, 352)
                out.writeInt(f.size)
                out.write(f)
            }
            val tail = Alac.encode(pcm(100, 50 * 352), 0, 100)
            out.writeInt(tail.size)
            out.write(tail)
        }
    }
}
