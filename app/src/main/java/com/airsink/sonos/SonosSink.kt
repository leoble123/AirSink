package com.airsink.sonos

import android.util.Log
import com.airsink.cast.AacEncoder
import com.airsink.cast.AudioSink
import com.airsink.cast.SinkState
import com.airsink.cast.StreamServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams the phone's audio to a Sonos group. Sonos pulls audio over HTTP, so we host a live
 * stream and point the group coordinator at it as if it were an internet radio station.
 *
 * AAC is the default (small, and Sonos treats it as radio). If the speaker doesn't start
 * playing it, we fall back to uncompressed WAV.
 */
class SonosSink(
    private val group: SonosGroup,
    private val preferWav: Boolean,
) : AudioSink {
    override val id = "sonos:${group.coordinator.uuid}"
    override val displayName = group.name

    private val _state = MutableStateFlow<SinkState>(SinkState.Stopped)
    override val state: StateFlow<SinkState> = _state.asStateFlow()
    private val _volume = MutableStateFlow(0.3f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()

    private val client = SonosClient(group.coordinator.host)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var server: StreamServer? = null
    @Volatile private var encoder: AacEncoder? = null
    @Volatile private var wav = false

    override suspend fun start() = withContext(Dispatchers.IO) {
        _state.value = SinkState.Connecting
        try {
            runCatching { _volume.value = client.getGroupVolume() / 100f }
            val formats = if (preferWav) listOf(true, false) else listOf(false, true)
            for (useWav in formats) {
                if (tryFormat(useWav)) {
                    _state.value = SinkState.Streaming
                    return@withContext
                }
            }
            throw SonosException("${group.name} didn't start playing the stream")
        } catch (e: Exception) {
            Log.w(TAG, "Sonos start failed", e)
            _state.value = SinkState.Failed(e.message ?: "Couldn't reach ${group.name}")
            shutdownStream()
            throw e
        }
    }

    private suspend fun tryFormat(useWav: Boolean): Boolean {
        shutdownStream()
        wav = useWav
        val srv = if (useWav) StreamServer("audio/wav") { wavHeader() } else StreamServer("audio/aac")
        server = srv
        if (!useWav) encoder = AacEncoder { srv.broadcast(it) }

        val ip = localAddressFor(group.coordinator.host)
        val url = "$ip:${srv.port}/stream.${if (useWav) "wav" else "aac"}"
        // The mp3radio scheme makes Sonos treat the URL as a live station (no seeking, no end).
        val uri = if (useWav) "http://$url" else "x-rincon-mp3radio://$url"
        client.setUri(uri, "AirSink · ${android.os.Build.MODEL}")
        client.play()

        repeat(16) {
            delay(500)
            if (client.transportState() == "PLAYING") return true
        }
        return false
    }

    override fun write(pcm: ByteArray, length: Int) {
        val srv = server ?: return
        if (wav) srv.broadcast(pcm.copyOf(length)) else encoder?.encode(pcm, length)
    }

    override fun setVolume(value: Float) {
        _volume.value = value.coerceIn(0f, 1f)
        scope.launch { runCatching { client.setGroupVolume((_volume.value * 100).toInt()) } }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        runCatching { client.stop() }
        shutdownStream()
        scope.cancel()
        _state.value = SinkState.Stopped
    }

    private fun shutdownStream() {
        encoder?.release()
        encoder = null
        server?.close()
        server = null
    }

    /** WAV header with "unknown" (maximal) sizes so the renderer treats it as endless. */
    private fun wavHeader(): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(-1)
        put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16)
        putShort(1); putShort(AudioSink.CHANNELS.toShort())
        putInt(AudioSink.SAMPLE_RATE); putInt(AudioSink.SAMPLE_RATE * AudioSink.BYTES_PER_FRAME)
        putShort(AudioSink.BYTES_PER_FRAME.toShort()); putShort(16)
        put("data".toByteArray()); putInt(-1)
    }.array()

    companion object {
        private const val TAG = "SonosSink"

        /** The phone's IP on the interface that routes to [host]. */
        fun localAddressFor(host: String): String = DatagramSocket().use { s ->
            s.connect(InetAddress.getByName(host), 1400)
            s.localAddress.hostAddress ?: "0.0.0.0"
        }
    }
}
