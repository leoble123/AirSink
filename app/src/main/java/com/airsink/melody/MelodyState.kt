package com.airsink.melody

data class MelodyBattery(val level: Int, val charging: Boolean)

enum class MelodyAnc(val code: Int, val label: String) {
    OFF(0x01, "Off"),
    TRANSPARENCY(0x02, "Transparency"),
    ON(0x08, "Noise Cancel");

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** Noise-cancellation strength on OnePlus buds (names as in the OnePlus app). */
enum class AncLevel(val label: String) {
    SMART("Smart"),
    MILD("Mild"),
    MODERATE("Moderate"),
    MAX("Max"),
    ADAPTIVE("Adaptive"),
}

enum class WearState { IN_EAR, OUT_OF_EAR, IN_CASE }

enum class TouchSide(val code: Int, val label: String) {
    LEFT(0x01, "Left"),
    RIGHT(0x02, "Right");

    companion object {
        fun fromCode(c: Int) = entries.firstOrNull { it.code == c }
    }
}

enum class Gesture(val code: Int, val label: String) {
    DOUBLE_TAP(0x0201, "Double Tap"),
    TRIPLE_TAP(0x0301, "Triple Tap"),
    HOLD(0x0401, "Press and Hold");

    companion object {
        fun fromCode(c: Int) = entries.firstOrNull { it.code == c }
    }
}

/** Actions a gesture can trigger. The voice assistant code differs between OPPO/OnePlus and realme. */
data class TouchAction(val code: Int, val label: String)

object TouchActions {
    val OFF = TouchAction(0x00, "None")
    val PLAY_PAUSE = TouchAction(0x01, "Play/Pause")
    val PREVIOUS = TouchAction(0x05, "Previous Track")
    val NEXT = TouchAction(0x06, "Next Track")
    val NOISE_CONTROL = TouchAction(0x08, "Noise Control")
    val VOLUME = TouchAction(0x07, "Volume")
    val VOLUME_UP = TouchAction(0x0B, "Volume Up")
    val VOLUME_DOWN = TouchAction(0x0C, "Volume Down")
    val GAME_MODE = TouchAction(0x11, "Game Mode")

    fun voiceAssistant(realme: Boolean) = TouchAction(if (realme) 0x04 else 0x03, "Voice Assistant")

    fun all(brand: MelodyState.Brand, anc: Boolean) = when (brand) {
        // OnePlus binds one "Volume" action per side rather than separate up/down.
        MelodyState.Brand.ONEPLUS -> listOfNotNull(
            OFF, PLAY_PAUSE, PREVIOUS, NEXT, VOLUME, voiceAssistant(false), NOISE_CONTROL.takeIf { anc }, GAME_MODE,
        )
        else -> listOfNotNull(
            OFF, PLAY_PAUSE, PREVIOUS, NEXT, VOLUME_UP, VOLUME_DOWN, voiceAssistant(brand == MelodyState.Brand.REALME),
            NOISE_CONTROL.takeIf { anc }, GAME_MODE,
        )
    }

    fun label(code: Int?, brand: MelodyState.Brand): String = when (code) {
        null -> "—"
        0x03, 0x04 -> "Voice Assistant"
        else -> (all(brand, true) + listOf(VOLUME, VOLUME_UP, VOLUME_DOWN)).firstOrNull { it.code == code }?.label ?: "Custom"
    }
}

data class MelodyState(
    val connection: Connection = Connection.DISCONNECTED,
    val name: String = "",
    val address: String? = null,
    val brand: Brand = Brand.ONEPLUS,
    val left: MelodyBattery? = null,
    val right: MelodyBattery? = null,
    val case: MelodyBattery? = null,
    val supportsAnc: Boolean = false,
    val anc: MelodyAnc? = null,
    /** OnePlus only: strength used when noise cancellation is on. */
    val ancLevel: AncLevel? = null,
    /** The raw ANC code, kept when it's a mode we don't have a name for (e.g. an ANC strength level). */
    val ancRaw: Int? = null,
    val cycleMask: Int? = null,
    val ldac: Boolean? = null,
    val multipoint: Boolean? = null,
    val gameMode: Boolean? = null,
    /** Buds pause/resume playback when taken out/put back (OnePlus "auto play/pause"). */
    val autoPause: Boolean? = null,
    val leftWear: WearState? = null,
    val rightWear: WearState? = null,
    val touch: Map<Pair<TouchSide, Gesture>, Int> = emptyMap(),
    val firmware: String? = null,
    val ringing: Boolean = false,
    val error: String? = null,
) {
    enum class Connection { DISCONNECTED, CONNECTING, CONNECTED, FAILED }
    enum class Brand(val label: String) { ONEPLUS("OnePlus"), OPPO("OPPO"), REALME("realme") }

    val isRealme get() = brand == Brand.REALME
    val isOnePlus get() = brand == Brand.ONEPLUS
}
