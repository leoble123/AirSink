package com.airsink.airpods

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AapClientTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val client = AapClient(CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun parsesBatteryPacket() {
        client.handlePacket(
            bytes(
                0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x03,
                0x02, 0x01, 0x5A, 0x02, 0x01, // right 90%, discharging
                0x04, 0x01, 0x3C, 0x01, 0x01, // left 60%, charging
                0x08, 0x01, 0x00, 0x04, 0x01, // case disconnected
            ),
        )
        val s = client.state.value
        assertEquals(90, s.right.level)
        assertEquals(60, s.left.level)
        assertTrue(s.left.charging)
        assertEquals(null, s.case.level)
    }

    @Test
    fun parsesNoiseModeAndEars() {
        client.handlePacket(bytes(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, 0x04, 0x00, 0x00, 0x00))
        assertEquals(NoiseMode.ADAPTIVE, client.state.value.noiseMode)
        client.handlePacket(bytes(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x28, 0x01, 0x00, 0x00, 0x00))
        assertEquals(true, client.state.value.conversationalAwareness)
        client.handlePacket(bytes(0x04, 0x00, 0x04, 0x00, 0x06, 0x00, 0x00, 0x01))
        assertEquals(EarState.IN_EAR, client.state.value.primaryEar)
        assertEquals(EarState.OUT_OF_EAR, client.state.value.secondaryEar)
    }
}
