package com.airsink.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Tiny SharedPreferences wrapper that exposes every setting as a StateFlow. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("airsink", Context.MODE_PRIVATE)

    inner class Pref<T>(private val key: String, default: T, private val read: (String, T) -> T, private val write: SharedPreferences.Editor.(String, T) -> Unit) {
        private val flow = MutableStateFlow(read(key, default))
        val value: T get() = flow.value
        val flowValue: StateFlow<T> get() = flow
        fun set(v: T) {
            flow.value = v
            sp.edit().apply { write(key, v) }.apply()
        }
    }

    private fun bool(key: String, default: Boolean) =
        Pref(key, default, { k, d -> sp.getBoolean(k, d) }, { k, v -> putBoolean(k, v) })

    private fun int(key: String, default: Int) =
        Pref(key, default, { k, d -> sp.getInt(k, d) }, { k, v -> putInt(k, v) })

    private fun string(key: String, default: String) =
        Pref(key, default, { k, d -> sp.getString(k, d) ?: d }, { k, v -> putString(k, v) })

    val onboarded = bool("onboarded", false)

    // Headphones
    val connectionPopup = bool("connection_popup", true)
    val earDetection = bool("ear_detection", true)
    val conversationDucking = bool("conversation_ducking", true)
    val advancedControls = bool("advanced_controls", true)
    val rssiThreshold = int("rssi_threshold", -65)

    // Casting
    val airplayLatencyMs = int("airplay_latency_ms", 2000)
    val sonosFormat = string("sonos_format", "aac")
    val deviceName = string(
        "device_name",
        // The friendly name set in Android settings, e.g. "Galaxy S24 FE".
        android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?: android.os.Build.MODEL ?: "Android",
    )

    // Appearance
    val theme = string("theme", "system")
}
