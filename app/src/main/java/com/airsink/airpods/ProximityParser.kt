package com.airsink.airpods

data class Battery(val level: Int?, val charging: Boolean = false) {
    val known get() = level != null

    companion object {
        val Unknown = Battery(null)
    }
}

/** A decoded Apple "proximity pairing" advertisement (Continuity message type 0x07). */
data class ProximityMessage(
    val model: AppleModel,
    val left: Battery,
    val right: Battery,
    val case: Battery,
    val leftInEar: Boolean,
    val rightInEar: Boolean,
    val lidOpen: Boolean,
    val colorCode: Int,
    val rssi: Int,
) {
    /** Single battery reading for devices without separate earbuds (AirPods Max, Beats over-ears). */
    val headset: Battery
        get() = listOf(left, right).filter { it.known }.maxByOrNull { it.level!! } ?: Battery.Unknown
}

/**
 * Parses the manufacturer-specific data Apple (company ID 0x004C) broadcasts from AirPods
 * and Beats. The layout below is the one documented by the OpenPods / furiousMAC Continuity
 * research; the first byte is the message type, not the company ID (Android strips that).
 *
 *  0     type (0x07)          5  status flags          8  lid state
 *  1     length (0x19)        6  pod batteries         9  color
 *  2     prefix               7  charging | case batt  10+ encrypted payload
 *  3..4  model (little-endian)
 */
object ProximityParser {
    const val APPLE_COMPANY_ID = 0x004C
    private const val TYPE_PROXIMITY_PAIRING = 0x07
    private const val EXPECTED_LENGTH = 27

    fun parse(data: ByteArray?, rssi: Int): ProximityMessage? {
        if (data == null || data.size != EXPECTED_LENGTH) return null
        if (data[0].toInt() and 0xFF != TYPE_PROXIMITY_PAIRING) return null

        val modelId = (data[4].u() shl 8) or data[3].u()
        val status = data[5].u()
        val pods = data[6].u()
        val chargeAndCase = data[7].u()
        val lid = data[8].u()

        // Bit 5 set means the "primary" pod (the one broadcasting) is the left one.
        val primaryLeft = status and 0x20 != 0
        val primary = pods and 0x0F
        val secondary = pods shr 4
        val chargeFlags = chargeAndCase shr 4

        val primaryBattery = Battery(level(primary), chargeFlags and 0x01 != 0)
        val secondaryBattery = Battery(level(secondary), chargeFlags and 0x02 != 0)
        val caseBattery = Battery(level(chargeAndCase and 0x0F), chargeFlags and 0x04 != 0)

        val primaryInEar = status and 0x02 != 0
        val secondaryInEar = status and 0x08 != 0

        return ProximityMessage(
            model = AppleModels.forId(modelId),
            left = if (primaryLeft) primaryBattery else secondaryBattery,
            right = if (primaryLeft) secondaryBattery else primaryBattery,
            case = caseBattery,
            leftInEar = if (primaryLeft) primaryInEar else secondaryInEar,
            rightInEar = if (primaryLeft) secondaryInEar else primaryInEar,
            lidOpen = (lid shr 3) and 0x01 == 0,
            colorCode = data[9].u(),
            rssi = rssi,
        )
    }

    /** Batteries are reported in 10% steps; 0xF means "not available" (e.g. pod out of range). */
    private fun level(nibble: Int): Int? = when (nibble) {
        in 0..10 -> nibble * 10
        else -> null
    }

    private fun Byte.u() = toInt() and 0xFF
}
