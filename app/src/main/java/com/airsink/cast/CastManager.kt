package com.airsink.cast

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.airsink.airplay.AirPlayDevice
import com.airsink.airplay.AirPlaySink
import com.airsink.core.Prefs
import com.airsink.sonos.SonosGroup
import com.airsink.sonos.SonosSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

sealed interface CastTarget {
    val id: String
    val name: String

    data class AirPlay(val device: AirPlayDevice) : CastTarget {
        override val id get() = "airplay:${device.id}"
        override val name get() = device.name
    }

    data class Sonos(val group: SonosGroup) : CastTarget {
        override val id get() = "sonos:${group.coordinator.uuid}"
        override val name get() = group.name
    }
}

/**
 * Owns the set of speakers currently receiving the phone's audio. A single capture stream
 * (run by [CastService]) is fanned out to every active sink, so several speakers can play at once.
 */
class CastManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
) {
    private val sinks = CopyOnWriteArrayList<AudioSink>()
    private val _active = MutableStateFlow<Map<String, AudioSink>>(emptyMap())
    val active: StateFlow<Map<String, AudioSink>> = _active.asStateFlow()

    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors.asStateFlow()

    /** Targets waiting for the user to grant screen-audio capture. */
    var pendingTargets: List<CastTarget> = emptyList()
        private set

    fun isActive(id: String) = _active.value.containsKey(id)

    /**
     * Adds or removes a speaker. Returns true when the caller must first ask the user for
     * audio-capture permission (MediaProjection), after which [onCaptureGranted] is called.
     */
    fun toggle(target: CastTarget): Boolean {
        if (isActive(target.id)) {
            remove(target.id)
            return false
        }
        if (!_capturing.value) {
            pendingTargets = listOf(target)
            return true
        }
        add(target)
        return false
    }

    fun onCaptureGranted(resultCode: Int, data: Intent) {
        val intent = Intent(context, CastService::class.java)
            .setAction(CastService.ACTION_START)
            .putExtra(CastService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CastService.EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(context, intent)
    }

    /** Called by the service once audio is flowing. */
    internal fun onCaptureStarted() {
        _capturing.value = true
        pendingTargets.forEach(::add)
        pendingTargets = emptyList()
    }

    internal fun onCaptureStopped() {
        _capturing.value = false
        val all = sinks.toList()
        sinks.clear()
        _active.value = emptyMap()
        scope.launch { all.forEach { runCatching { it.stop() } } }
    }

    private fun add(target: CastTarget) {
        val sink: AudioSink = when (target) {
            is CastTarget.AirPlay -> AirPlaySink(target.device, prefs.deviceName.value, prefs.airplayLatencyMs.value)
            is CastTarget.Sonos -> SonosSink(target.group, preferWav = prefs.sonosFormat.value == "wav")
        }
        _active.update { it + (sink.id to sink) }
        _errors.value = null
        scope.launch {
            try {
                sink.start()
                sinks += sink
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start ${target.name}", e)
                _errors.value = (sink.state.value as? SinkState.Failed)?.message ?: e.message
                _active.update { it - sink.id }
                if (_active.value.isEmpty()) stopAll()
            }
        }
    }

    fun remove(id: String) {
        val sink = _active.value[id] ?: return
        sinks -= sink
        _active.update { it - id }
        scope.launch { runCatching { sink.stop() } }
        if (_active.value.isEmpty()) stopAll()
    }

    fun stopAll() {
        context.startService(Intent(context, CastService::class.java).setAction(CastService.ACTION_STOP))
    }

    fun clearError() { _errors.value = null }

    /** Called on the capture thread with fresh PCM. */
    internal fun dispatch(pcm: ByteArray, length: Int) {
        for (sink in sinks) {
            try {
                sink.write(pcm, length)
            } catch (e: Exception) {
                Log.w(TAG, "Sink ${sink.displayName} write failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "CastManager"
    }
}
