package com.airsink.airpods

enum class FormFactor { EARBUDS, OVER_EAR, EARHOOK }

data class AppleModel(
    val id: Int,
    val name: String,
    val formFactor: FormFactor = FormFactor.EARBUDS,
    val hasCase: Boolean = formFactor != FormFactor.OVER_EAR,
    val anc: Boolean = false,
    val adaptive: Boolean = false,
    val conversationalAwareness: Boolean = false,
    val isBeats: Boolean = false,
)

/**
 * Model IDs as they appear (byte-swapped) in Apple's proximity pairing BLE advertisement.
 * Sourced from the community reverse-engineering of the Continuity protocol.
 */
object AppleModels {
    private val models = listOf(
        AppleModel(0x2002, "AirPods"),
        AppleModel(0x200F, "AirPods (2nd gen)"),
        AppleModel(0x2013, "AirPods (3rd gen)"),
        AppleModel(0x2019, "AirPods 4"),
        AppleModel(0x201B, "AirPods 4", anc = true, adaptive = true, conversationalAwareness = true),
        AppleModel(0x200E, "AirPods Pro", anc = true),
        AppleModel(0x2014, "AirPods Pro 2", anc = true, adaptive = true, conversationalAwareness = true),
        AppleModel(0x2024, "AirPods Pro 2", anc = true, adaptive = true, conversationalAwareness = true),
        AppleModel(0x2027, "AirPods Pro 3", anc = true, adaptive = true, conversationalAwareness = true),
        AppleModel(0x200A, "AirPods Max", FormFactor.OVER_EAR, anc = true),
        AppleModel(0x201F, "AirPods Max", FormFactor.OVER_EAR, anc = true, adaptive = true, conversationalAwareness = true),
        AppleModel(0x2003, "Powerbeats³", FormFactor.EARHOOK, hasCase = false, isBeats = true),
        AppleModel(0x200B, "Powerbeats Pro", FormFactor.EARHOOK, isBeats = true),
        AppleModel(0x201D, "Powerbeats Pro 2", FormFactor.EARHOOK, anc = true, isBeats = true),
        AppleModel(0x200C, "Beats Solo Pro", FormFactor.OVER_EAR, anc = true, isBeats = true),
        AppleModel(0x2005, "BeatsX", hasCase = false, isBeats = true),
        AppleModel(0x2006, "Beats Solo³", FormFactor.OVER_EAR, isBeats = true),
        AppleModel(0x2009, "Beats Studio³", FormFactor.OVER_EAR, anc = true, isBeats = true),
        AppleModel(0x2010, "Beats Flex", hasCase = false, isBeats = true),
        AppleModel(0x2011, "Beats Studio Buds", anc = true, isBeats = true),
        AppleModel(0x2012, "Beats Fit Pro", anc = true, isBeats = true),
        AppleModel(0x2016, "Beats Studio Buds+", anc = true, isBeats = true),
        AppleModel(0x2017, "Beats Studio Pro", FormFactor.OVER_EAR, anc = true, isBeats = true),
        AppleModel(0x2025, "Beats Solo 4", FormFactor.OVER_EAR, isBeats = true),
        AppleModel(0x2026, "Beats Solo Buds", isBeats = true),
    ).associateBy { it.id }

    fun forId(id: Int): AppleModel =
        models[id] ?: AppleModel(id, "Apple Headphones")

    /** Best-effort match for a bonded Bluetooth device we only know by name. */
    fun guessFromName(name: String?): AppleModel? {
        val n = name?.lowercase() ?: return null
        return when {
            "airpods max" in n -> models[0x201F]
            "airpods pro" in n -> models[0x2024]
            "airpods" in n -> models[0x2019]
            "beats" in n || "powerbeats" in n -> AppleModel(0, name, isBeats = true)
            else -> null
        }
    }
}
