package com.airsink.cast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/** Encodes 44.1 kHz stereo PCM to AAC-LC and emits self-contained ADTS frames. */
class AacEncoder(bitrate: Int = 256_000, private val onFrame: (ByteArray) -> Unit) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioSink.SAMPLE_RATE, AudioSink.CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    private val info = MediaCodec.BufferInfo()
    private var framesIn = 0L

    @Synchronized
    fun encode(pcm: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) { drain(); continue }
            val buffer = codec.getInputBuffer(index)!!
            buffer.clear()
            val n = minOf(buffer.remaining(), length - offset)
            buffer.put(pcm, offset, n)
            val ptsUs = framesIn * 1_000_000L / AudioSink.SAMPLE_RATE
            codec.queueInputBuffer(index, 0, n, ptsUs, 0)
            framesIn += n / AudioSink.BYTES_PER_FRAME
            offset += n
            drain()
        }
    }

    private fun drain() {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val out = codec.getOutputBuffer(index)!!
                val frame = ByteArray(info.size + 7)
                writeAdtsHeader(frame, frame.size)
                out.position(info.offset)
                out.get(frame, 7, info.size)
                onFrame(frame)
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    companion object {
        /** ADTS header for AAC-LC, 44.1 kHz (index 4), stereo. */
        fun writeAdtsHeader(buf: ByteArray, packetLength: Int) {
            val profile = 2 // AAC LC
            val freqIndex = 4
            val channels = 2
            buf[0] = 0xFF.toByte()
            buf[1] = 0xF1.toByte()
            buf[2] = (((profile - 1) shl 6) + (freqIndex shl 2) + (channels shr 2)).toByte()
            buf[3] = (((channels and 3) shl 6) + (packetLength shr 11)).toByte()
            buf[4] = ((packetLength and 0x7FF) shr 3).toByte()
            buf[5] = (((packetLength and 7) shl 5) + 0x1F).toByte()
            buf[6] = 0xFC.toByte()
        }
    }
}
