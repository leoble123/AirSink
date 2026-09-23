package com.airsink.melody

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The control protocol spoken by OPPO-made earbuds: OnePlus Buds, OPPO Enco and realme Buds
 * (the family managed by the HeyMelody app). Runs over RFCOMM.
 *
 * Frame: `AA len 00 00 cmd:u16le seq:u8 payloadLen:u16le payload`, where len counts every
 * byte after itself. The wire format was documented by Gadgetbridge's reverse engineering.
 */
object MelodyProtocol {
    const val SERVICE_UUID = "0000079a-d102-11e1-9b23-00025b00a5a5"
    private const val PREAMBLE = 0xAA

    object Cmd {
        const val BATTERY_REQ = 0x0106
        const val BATTERY_RET = 0x8106
        const val FIRMWARE_REQ = 0x0105
        const val FIRMWARE_RET = 0x8105
        const val SUBSCRIBE = 0x0205
        const val SUBSCRIPTION_EVENT = 0x0204
        const val TOUCH_REQ = 0x0108
        const val TOUCH_RET = 0x8108
        const val TOUCH_SET = 0x0401
        const val FIND_DEVICE = 0x0400
        const val MISC_SET = 0x0403
        const val MISC_REQ = 0x010D
        const val MISC_RET = 0x810D
        const val ANC_SET = 0x0404
        const val ANC_REQ = 0x010C
        const val ANC_RET = 0x810C
    }

    object Misc {
        const val GAME_MODE = 0x06
        const val MULTIPOINT = 0x11
        const val LDAC = 0x18
    }

    object Subscription {
        const val BATTERY = 0x01
        const val STATUS = 0x02
        const val ANC = 0x03
        const val GAME_MODE = 0x05
    }

    private const val ANC_TYPE_MODE = 0x01
    private const val ANC_TYPE_CYCLE = 0x02

    data class Frame(val command: Int, val seq: Int, val payload: ByteArray)

    // ---- Encoding --------------------------------------------------------------------

    fun encode(command: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val buf = ByteBuffer.allocate(9 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(PREAMBLE.toByte())
        buf.put((7 + payload.size).toByte())
        buf.putShort(0)
        buf.putShort(command.toShort())
        buf.put(seq.toByte())
        buf.putShort(payload.size.toShort())
        buf.put(payload)
        return buf.array()
    }

    fun ancModeSet(mode: MelodyAnc) = byteArrayOf(ANC_TYPE_MODE.toByte(), 0x01, mode.code.toByte())
    fun ancCycleSet(mask: Int) = byteArrayOf(ANC_TYPE_CYCLE.toByte(), 0x01, mask.toByte())
    fun ancModeReq() = byteArrayOf(ANC_TYPE_MODE.toByte(), 0x01)
    fun ancCycleReq() = byteArrayOf(ANC_TYPE_CYCLE.toByte(), 0x01)
    fun miscSet(type: Int, on: Boolean) = byteArrayOf(type.toByte(), if (on) 1 else 0)
    fun miscReq(vararg types: Int) = byteArrayOf(types.size.toByte()) + ByteArray(types.size) { types[it].toByte() }
    fun subscribe(vararg types: Int) = byteArrayOf(0x09) + ByteArray(types.size) { types[it].toByte() }
    fun touchReq() = byteArrayOf(0x02, 0x03, 0x01)
    fun touchSet(side: TouchSide, gesture: Gesture, action: Int): ByteArray =
        ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x01).put(side.code.toByte()).putShort(gesture.code.toShort()).put(action.toByte())
            .array()
    fun findDevice(ring: Boolean) = byteArrayOf(if (ring) 1 else 0)

    // ---- Decoding --------------------------------------------------------------------

    /**
     * Pulls every complete frame out of [buffer], leaving any trailing partial frame in it
     * (RFCOMM reads don't respect frame boundaries).
     */
    fun drainFrames(buffer: ByteArrayOutputStream): List<Frame> {
        val data = buffer.toByteArray()
        val frames = ArrayList<Frame>()
        var i = 0
        while (i < data.size) {
            if (data[i].toInt() and 0xFF != PREAMBLE) { i++; continue }
            if (i + 2 > data.size) break
            val total = (data[i + 1].toInt() and 0xFF) + 2
            if (i + total > data.size) break
            parseFrame(data, i, total)?.let(frames::add)
            i += total
        }
        buffer.reset()
        if (i < data.size) buffer.write(data, i, data.size - i)
        return frames
    }

    private fun parseFrame(d: ByteArray, start: Int, total: Int): Frame? {
        if (total < 9) return null
        val b = ByteBuffer.wrap(d, start, total).order(ByteOrder.LITTLE_ENDIAN)
        b.position(start + 4) // preamble, length and the two flag bytes (0 on OPPO, 4 on realme)
        val command = b.short.toInt() and 0xFFFF
        val seq = b.get().toInt() and 0xFF
        val len = b.short.toInt() and 0xFFFF
        val available = start + total - b.position()
        val payload = ByteArray(minOf(len, available))
        b.get(payload)
        return Frame(command, seq, payload)
    }

    /** Applies a decoded frame to [state]; returns the updated state. */
    fun apply(state: MelodyState, f: Frame): MelodyState {
        val p = f.payload
        fun u(i: Int) = p[i].toInt() and 0xFF
        return when (f.command) {
            Cmd.BATTERY_RET -> if (p.size >= 2 && p[0].toInt() == 0) batteries(state, p) else state
            Cmd.SUBSCRIPTION_EVENT -> when (if (p.isNotEmpty()) u(0) else -1) {
                Subscription.BATTERY -> batteries(state, p)
                Subscription.ANC -> if (p.size >= 3) state.copy(anc = MelodyAnc.fromCode(u(2)), ancRaw = u(2)) else state
                Subscription.GAME_MODE -> if (p.size >= 2) state.copy(gameMode = u(1) == 1) else state
                else -> state
            }
            Cmd.ANC_RET -> if (p.size >= 4 && p[0].toInt() == 0) {
                when (u(1)) {
                    ANC_TYPE_MODE -> state.copy(anc = MelodyAnc.fromCode(u(3)), ancRaw = u(3), supportsAnc = true)
                    ANC_TYPE_CYCLE -> state.copy(cycleMask = u(3), supportsAnc = true)
                    else -> state
                }
            } else state
            Cmd.MISC_RET -> if (p.size >= 3 && p[0].toInt() == 0) {
                var s = state
                var i = 2
                while (i + 1 < p.size) {
                    val on = u(i + 1) == 1
                    s = when (u(i)) {
                        Misc.LDAC -> s.copy(ldac = on)
                        Misc.MULTIPOINT -> s.copy(multipoint = on)
                        Misc.GAME_MODE -> s.copy(gameMode = on)
                        else -> s
                    }
                    i += 2
                }
                s
            } else state
            Cmd.TOUCH_RET -> if (p.size >= 2 && p[0].toInt() == 0) {
                val map = state.touch.toMutableMap()
                var i = 2
                while (i + 3 < p.size) {
                    val side = TouchSide.fromCode(u(i))
                    val gesture = Gesture.fromCode(u(i + 1) or (u(i + 2) shl 8))
                    if (side != null && gesture != null) map[side to gesture] = u(i + 3)
                    i += 4
                }
                state.copy(touch = map)
            } else state
            Cmd.FIRMWARE_RET -> if (p.size > 2 && p[0].toInt() == 0) state.copy(firmware = firmware(p)) else state
            else -> state
        }
    }

    /** `[type/status][count] ([index][level|charging<<7])*`, index 1=left 2=right 3=case. */
    private fun batteries(state: MelodyState, p: ByteArray): MelodyState {
        var s = state
        var i = 2
        while (i + 1 < p.size) {
            val index = p[i].toInt() and 0xFF
            val raw = p[i + 1].toInt() and 0xFF
            val level = raw and 0x7F
            val battery = MelodyBattery(level.coerceIn(0, 100), raw and 0x80 != 0)
            s = when (index) {
                1 -> s.copy(left = battery)
                2 -> s.copy(right = battery)
                3 -> if (level == 0) s else s.copy(case = battery) // 0 means "case not reporting"
                else -> s
            }
            i += 2
        }
        return s
    }

    /** "part,type,version" triples; type 2 is firmware. */
    private fun firmware(p: ByteArray): String {
        val text = String(p, 2, p.size - 2, Charsets.US_ASCII).trim('\u0000', ' ')
        val parts = text.split(",")
        if (parts.size % 3 != 0) return text
        val versions = parts.chunked(3).filter { it[1] == "2" }.map { it[2] }.distinct()
        return versions.firstOrNull { "." in it } ?: versions.joinToString(".").ifEmpty { text }
    }
}
