package com.airsink.melody

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MelodyProtocolTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** A frame as the earbuds would send it. */
    private fun frame(cmd: Int, payload: ByteArray, flags: Int = 0): ByteArray =
        bytes(0xAA, 7 + payload.size, flags, 0, cmd and 0xFF, cmd shr 8, 0x01, payload.size, 0) + payload

    private fun feed(state: MelodyState, vararg frames: ByteArray): MelodyState {
        val buf = ByteArrayOutputStream()
        frames.forEach { buf.write(it) }
        return MelodyProtocol.drainFrames(buf).fold(state) { s, f -> MelodyProtocol.apply(s, f) }
    }

    @Test
    fun encodesFrames() {
        assertArrayEquals(
            bytes(0xAA, 0x07, 0x00, 0x00, 0x06, 0x01, 0x05, 0x00, 0x00),
            MelodyProtocol.encode(MelodyProtocol.Cmd.BATTERY_REQ, 5),
        )
        assertArrayEquals(
            bytes(0xAA, 0x0A, 0x00, 0x00, 0x04, 0x04, 0x02, 0x03, 0x00, 0x01, 0x01, 0x08),
            MelodyProtocol.encode(MelodyProtocol.Cmd.ANC_SET, 2, MelodyProtocol.ancModeSet(MelodyAnc.ON)),
        )
        // Touch set: [1][side][gesture u16 LE][action]
        assertArrayEquals(bytes(0x01, 0x02, 0x01, 0x04, 0x06), MelodyProtocol.touchSet(TouchSide.RIGHT, Gesture.HOLD, 0x06))
    }

    @Test
    fun parsesBatteryReplyAndEvents() {
        val s = feed(
            MelodyState(),
            // Reply: status 0, three batteries: left 80, right 75 charging, case 40.
            frame(MelodyProtocol.Cmd.BATTERY_RET, bytes(0x00, 0x03, 0x01, 80, 0x02, 0x80 or 75, 0x03, 40)),
        )
        assertEquals(MelodyBattery(80, false), s.left)
        assertEquals(MelodyBattery(75, true), s.right)
        assertEquals(MelodyBattery(40, false), s.case)

        // Subscription event (type 1 = battery) from a realme device (flags = 4); case 0 is ignored.
        val s2 = feed(s, frame(MelodyProtocol.Cmd.SUBSCRIPTION_EVENT, bytes(0x01, 0x02, 0x01, 79, 0x03, 0), flags = 4))
        assertEquals(79, s2.left?.level)
        assertEquals(40, s2.case?.level)
    }

    private val oppo = MelodyState(brand = MelodyState.Brand.OPPO)
    private val onePlus = MelodyState(brand = MelodyState.Brand.ONEPLUS)

    @Test
    fun parsesAncAndSettings() {
        val s = feed(
            oppo,
            frame(MelodyProtocol.Cmd.ANC_RET, bytes(0x00, 0x01, 0x01, 0x02)),
            frame(MelodyProtocol.Cmd.ANC_RET, bytes(0x00, 0x02, 0x01, 0x0A)),
            frame(MelodyProtocol.Cmd.MISC_RET, bytes(0x00, 0x03, 0x18, 0x01, 0x11, 0x00, 0x06, 0x01)),
        )
        assertTrue(s.supportsAnc)
        assertEquals(MelodyAnc.TRANSPARENCY, s.anc)
        assertEquals(0x0A, s.cycleMask)
        assertEquals(true, s.ldac)
        assertEquals(false, s.multipoint)
        assertEquals(true, s.gameMode)

        val s2 = feed(s, frame(MelodyProtocol.Cmd.SUBSCRIPTION_EVENT, bytes(0x03, 0x01, 0x08)))
        assertEquals(MelodyAnc.ON, s2.anc)
    }

    @Test
    fun parsesGesturesAndFirmware() {
        val s = feed(
            MelodyState(),
            frame(MelodyProtocol.Cmd.TOUCH_RET, bytes(0x00, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02, 0x01, 0x04, 0x0B)),
            frame(MelodyProtocol.Cmd.FIRMWARE_RET, bytes(0x00, 0x00) + "1,2,1.2.3,2,2,1.2.3,1,1,HW\u0000".toByteArray()),
        )
        assertEquals(0x01, s.touch[TouchSide.LEFT to Gesture.DOUBLE_TAP])
        assertEquals(0x0B, s.touch[TouchSide.RIGHT to Gesture.HOLD])
        assertEquals("1.2.3", s.firmware)
    }

    @Test
    fun reassemblesSplitFrames() {
        val full = frame(MelodyProtocol.Cmd.BATTERY_RET, bytes(0x00, 0x01, 0x01, 55))
        val buf = ByteArrayOutputStream()
        buf.write(full, 0, 5)
        assertEquals(0, MelodyProtocol.drainFrames(buf).size)
        buf.write(full, 5, full.size - 5)
        val frames = MelodyProtocol.drainFrames(buf)
        assertEquals(1, frames.size)
        assertEquals(55, MelodyProtocol.apply(MelodyState(), frames[0]).left?.level)
        assertEquals(0, buf.size())
    }

    // ---- OnePlus: byte sequences captured from OnePlus buds by the QuickBuds project ----

    @Test
    fun onePlusAncSetUsesItsOwnBitTable() {
        val b = MelodyState.Brand.ONEPLUS
        assertArrayEquals(bytes(0x01, 0x01, 0x01), MelodyProtocol.ancModeSet(MelodyAnc.OFF, b))
        assertArrayEquals(bytes(0x01, 0x01, 0x04), MelodyProtocol.ancModeSet(MelodyAnc.TRANSPARENCY, b))
        assertArrayEquals(bytes(0x01, 0x01, 0x02), MelodyProtocol.ancModeSet(MelodyAnc.ON, b))
        assertArrayEquals(bytes(0x01, 0x01, 0x10), MelodyProtocol.ancModeSet(MelodyAnc.ON, b, AncLevel.MAX))
        assertArrayEquals(bytes(0x01, 0x01, 0x80), MelodyProtocol.ancModeSet(MelodyAnc.ON, b, AncLevel.SMART))
        assertArrayEquals(bytes(0x01, 0x01, 0x00, 0x08), MelodyProtocol.ancModeSet(MelodyAnc.ON, b, AncLevel.ADAPTIVE))
        // HeyMelody's own capture: add Adaptive to the hold cycle -> `02 01 07 08`.
        assertArrayEquals(bytes(0x02, 0x01, 0x07, 0x08), MelodyProtocol.ancCycleSet(0x0807, b))
        assertArrayEquals(bytes(0x03, 0x01, 0x02, 0x03), MelodyProtocol.subscribe(b))
        // OPPO keeps its own values.
        assertArrayEquals(bytes(0x01, 0x01, 0x08), MelodyProtocol.ancModeSet(MelodyAnc.ON, MelodyState.Brand.OPPO))
    }

    @Test
    fun onePlusAncReportsUseTheQueryTable() {
        fun event(lo: Int, hi: Int) = frame(MelodyProtocol.Cmd.SUBSCRIPTION_EVENT, bytes(0x03, 0x01, 0x01, lo, hi))
        // The bug this fixes: an "off" report used to be misread, and every report looked like Off.
        assertEquals(MelodyAnc.OFF, feed(onePlus, event(0x08, 0x00)).anc)
        assertEquals(MelodyAnc.TRANSPARENCY, feed(onePlus, event(0x00, 0x01)).anc)
        feed(onePlus, event(0x10, 0x00)).let { assertEquals(MelodyAnc.ON, it.anc); assertEquals(AncLevel.MAX, it.ancLevel) }
        feed(onePlus, event(0x00, 0x08)).let { assertEquals(MelodyAnc.ON, it.anc); assertEquals(AncLevel.ADAPTIVE, it.ancLevel) }

        val q = feed(
            onePlus,
            frame(MelodyProtocol.Cmd.ANC_RET, bytes(0x00, 0x01, 0x01, 0x20, 0x00)),
            frame(MelodyProtocol.Cmd.ANC_RET, bytes(0x00, 0x02, 0x01, 0x07, 0x00)),
        )
        assertEquals(MelodyAnc.ON, q.anc)
        assertEquals(AncLevel.MODERATE, q.ancLevel)
        assertEquals(0x07, q.cycleMask)
        assertTrue(q.supportsAnc)
    }

    @Test
    fun onePlusWearAndBattery() {
        val s = feed(
            onePlus,
            frame(MelodyProtocol.Cmd.SUBSCRIPTION_EVENT, bytes(0x02, 0x02, 0x01, 0x03, 0x02, 0x04)),
            // Battery reply without the leading status byte.
            frame(MelodyProtocol.Cmd.BATTERY_RET, bytes(0x01, 80, 0x02, 0x80 or 75)),
        )
        assertEquals(WearState.IN_EAR, s.leftWear)
        assertEquals(WearState.IN_CASE, s.rightWear)
        assertEquals(80, s.left?.level)
        assertEquals(MelodyBattery(75, true), s.right)
    }
}
