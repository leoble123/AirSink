package com.airsink.airpods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityParserTest {

    private fun message(status: Int, pods: Int, chargeAndCase: Int, lid: Int = 0x00, model: Int = 0x2014): ByteArray {
        val b = ByteArray(27)
        b[0] = 0x07; b[1] = 0x19; b[2] = 0x01
        b[3] = (model and 0xFF).toByte(); b[4] = (model shr 8).toByte()
        b[5] = status.toByte(); b[6] = pods.toByte(); b[7] = chargeAndCase.toByte(); b[8] = lid.toByte()
        return b
    }

    @Test
    fun parsesBatteriesWhenLeftIsPrimary() {
        // Primary (left) 80%, secondary (right) 70%, case 50% and charging, left in ear.
        val msg = ProximityParser.parse(message(status = 0x22, pods = 0x78, chargeAndCase = 0x45), -50)!!
        assertEquals("AirPods Pro 2", msg.model.name)
        assertEquals(80, msg.left.level)
        assertEquals(70, msg.right.level)
        assertEquals(50, msg.case.level)
        assertTrue(msg.case.charging)
        assertFalse(msg.left.charging)
        assertTrue(msg.leftInEar)
        assertFalse(msg.rightInEar)
        assertTrue(msg.lidOpen)
    }

    @Test
    fun swapsSidesWhenRightIsPrimary() {
        val msg = ProximityParser.parse(message(status = 0x02, pods = 0x78, chargeAndCase = 0x15), -50)!!
        assertEquals(80, msg.right.level)
        assertEquals(70, msg.left.level)
        assertTrue(msg.right.charging)
        assertTrue(msg.rightInEar)
        assertFalse(msg.leftInEar)
    }

    @Test
    fun unavailableBatteriesAreNull() {
        val msg = ProximityParser.parse(message(status = 0x20, pods = 0xFA, chargeAndCase = 0x0F, lid = 0x08), -50)!!
        assertEquals(100, msg.left.level)
        assertNull(msg.right.level)
        assertNull(msg.case.level)
        assertFalse(msg.lidOpen)
    }

    @Test
    fun rejectsOtherMessages() {
        assertNull(ProximityParser.parse(ByteArray(27).also { it[0] = 0x10 }, -40))
        assertNull(ProximityParser.parse(ByteArray(10).also { it[0] = 0x07 }, -40))
        assertNull(ProximityParser.parse(null, -40))
    }
}
