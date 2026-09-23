package com.airsink.cast

import kotlinx.coroutines.flow.StateFlow

sealed interface SinkState {
    data object Connecting : SinkState
    data object Streaming : SinkState
    data object Stopped : SinkState
    data class Failed(val message: String) : SinkState
}

/**
 * A destination for the phone's audio. Input is always 44.1 kHz, 16-bit, interleaved stereo PCM,
 * which is what AirPlay wants natively and what we encode to AAC for Sonos.
 */
interface AudioSink {
    val id: String
    val displayName: String
    val state: StateFlow<SinkState>
    val volume: StateFlow<Float>

    /** Connects and negotiates the stream; throws on failure. */
    suspend fun start()

    /** Called from the capture thread; must not block for long. */
    fun write(pcm: ByteArray, length: Int)

    fun setVolume(value: Float)

    suspend fun stop()

    companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = 4
    }
}
