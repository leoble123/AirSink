package com.airsink.airplay

import android.util.Log
import com.airsink.cast.AudioSink
import com.airsink.cast.SinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

/**
 * AirPlay 2 sender for a single receiver (HomePod, Apple TV, AirPlay-capable Sonos, etc).
 *
 * Flow: transient pair-setup (SRP with the fixed PIN 3939) → encrypted RTSP control channel →
 * SETUP session (NTP timing) → event channel → SETUP realtime audio stream (type 96, ALAC) →
 * RECORD → RTP audio over UDP encrypted with ChaCha20-Poly1305, with sync, timing and
 * retransmission handled on side channels. This mirrors what OwnTone does for AirPlay 2.
 */
class AirPlaySink(
    private val device: AirPlayDevice,
    private val senderName: String,
    private val latencyMs: Int,
) : AudioSink {
    override val id = "airplay:${device.id}"
    override val displayName = device.name

    private val _state = MutableStateFlow<SinkState>(SinkState.Stopped)
    override val state: StateFlow<SinkState> = _state.asStateFlow()
    private val _volume = MutableStateFlow(0.6f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()

    private val random = SecureRandom()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var rtsp: RtspConnection? = null
    private var eventSocket: Socket? = null
    private lateinit var sessionUri: String
    private val dacpId = "%016X".format(random.nextLong())
    private val activeRemote = (random.nextInt() ushr 1).toString()
    private val audioKey = ByteArray(32).also(random::nextBytes)

    private lateinit var timingSocket: DatagramSocket
    private lateinit var controlSocket: DatagramSocket
    private lateinit var dataSocket: DatagramSocket
    private lateinit var remote: InetAddress
    private var remoteDataPort = 0
    private var remoteControlPort = 0

    // RTP state
    private var seq = random.nextInt(0xFFFF)
    private var rtpTime = random.nextInt() and 0x7FFFFFFF
    private val ssrc = random.nextInt()
    private var packetCounter = 0L
    private var firstPacket = true
    private val latencyFrames = (latencyMs * AudioSink.SAMPLE_RATE / 1000L).toInt()
    private val history = arrayOfNulls<ByteArray>(1024)

    // Clock anchor: which RTP timestamp corresponds to which monotonic time.
    private var anchorNanos = 0L
    private var anchorRtp = 0
    private val ntpBaseSeconds = System.currentTimeMillis() / 1000.0 + NTP_EPOCH_OFFSET
    private val ntpBaseNanos = System.nanoTime()

    private val pending = ByteArray(Alac.FRAMES_PER_PACKET * AudioSink.BYTES_PER_FRAME)
    private var pendingLen = 0

    override suspend fun start() = withContext(Dispatchers.IO) {
        _state.value = SinkState.Connecting
        try {
            connect()
            _state.value = SinkState.Streaming
        } catch (e: Exception) {
            Log.w(TAG, "AirPlay start failed for ${device.name}", e)
            _state.value = SinkState.Failed(explain(e))
            teardown()
            throw e
        }
    }

    private fun connect() {
        remote = InetAddress.getByName(device.host)
        val conn = RtspConnection(device.host, device.port)
        rtsp = conn
        conn.extraHeaders = mapOf(
            "User-Agent" to "AirPlay/409.16",
            "X-Apple-ProtocolVersion" to "1",
            "DACP-ID" to dacpId,
            "Active-Remote" to activeRemote,
            "Client-Instance" to dacpId,
        )
        sessionUri = "rtsp://${conn.localAddress}/${random.nextInt() ushr 1}"

        val sharedSecret = pairTransient(conn)
        conn.enableEncryption(
            writeKey = Crypto.hkdf(sharedSecret, "Control-Salt", "Control-Write-Encryption-Key"),
            readKey = Crypto.hkdf(sharedSecret, "Control-Salt", "Control-Read-Encryption-Key"),
        )

        timingSocket = DatagramSocket(0)
        controlSocket = DatagramSocket(0)
        dataSocket = DatagramSocket(0)
        scope.launch { timingResponder() }

        // 1. Session setup
        val macAddress = dacpId.chunked(2).take(6).joinToString(":")
        val session = conn.request(
            "SETUP", sessionUri, contentType = PLIST,
            body = BPlist.encode(
                mapOf(
                    "deviceID" to macAddress,
                    "macAddress" to macAddress,
                    "sessionUUID" to UUID.randomUUID().toString().uppercase(),
                    "groupUUID" to UUID.randomUUID().toString().uppercase(),
                    "groupContainsGroupLeader" to false,
                    "isMultiSelectAirPlay" to true,
                    "timingProtocol" to "NTP",
                    "timingPort" to timingSocket.localPort,
                    "model" to "iPhone16,2",
                    "name" to senderName,
                    "osName" to "iPhone OS",
                    "osVersion" to "18.0",
                    "osBuildVersion" to "22A3354",
                    "sourceVersion" to "409.16",
                    "senderSupportsRelay" to false,
                    "statsCollectionEnabled" to false,
                ),
            ),
        ).requireOk("SETUP session")
        val eventPort = (session.plist()?.get("eventPort") as? Number)?.toInt() ?: 0
        if (eventPort > 0) openEventChannel(eventPort, sharedSecret)

        // 2. Audio stream setup
        val stream = conn.request(
            "SETUP", sessionUri, contentType = PLIST,
            body = BPlist.encode(
                mapOf(
                    "streams" to listOf(
                        mapOf(
                            "type" to 96,
                            "ct" to 2,                     // ALAC
                            "audioFormat" to 0x40000,      // ALAC 44100/16/2
                            "sr" to AudioSink.SAMPLE_RATE,
                            "spf" to Alac.FRAMES_PER_PACKET,
                            "audioMode" to "default",
                            "controlPort" to controlSocket.localPort,
                            "isMedia" to true,
                            "latencyMin" to 11025,
                            "latencyMax" to 88200,
                            "shk" to audioKey,
                            "supportsDynamicStreamID" to false,
                            "streamConnectionID" to (random.nextLong() ushr 1),
                        ),
                    ),
                ),
            ),
        ).requireOk("SETUP stream")
        val streamInfo = (stream.plist()?.get("streams") as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: throw IllegalStateException("Receiver did not accept the audio stream")
        remoteDataPort = (streamInfo["dataPort"] as Number).toInt()
        remoteControlPort = (streamInfo["controlPort"] as Number).toInt()

        conn.request(
            "RECORD", sessionUri,
            headers = mapOf("Range" to "npt=0-", "RTP-Info" to "seq=$seq;rtptime=$rtpTime"),
        ).requireOk("RECORD")

        sendVolume(_volume.value)
        anchorNanos = System.nanoTime()
        anchorRtp = rtpTime

        scope.launch { feedbackLoop() }
        scope.launch { syncLoop() }
        scope.launch { controlReceiver() }
    }

    /** Transient HomeKit pair-setup: no stored keys, fixed PIN 3939, yields a 64-byte secret. */
    private fun pairTransient(conn: RtspConnection): ByteArray {
        val headers = mapOf("X-Apple-HKP" to "4")
        val m2 = conn.request(
            "POST", "/pair-setup", contentType = OCTET, headers = headers,
            body = Tlv8.encode(
                Tlv8.METHOD to Tlv8.byte(0),
                Tlv8.STATE to Tlv8.byte(1),
                Tlv8.FLAGS to Tlv8.byte(0x10), // transient
            ),
        ).requireOk("pair-setup M1")
        val m2Tlv = Tlv8.decode(m2.body)
        m2Tlv[Tlv8.ERROR]?.let { throw PairingException(it.firstOrNull()?.toInt() ?: -1) }
        val salt = m2Tlv[Tlv8.SALT] ?: throw PairingException(-1)
        val serverKey = m2Tlv[Tlv8.PUBLIC_KEY] ?: throw PairingException(-1)

        val srp = SrpClient("Pair-Setup", "3939", random)
        val proof = srp.proof(salt, serverKey)
        val m4 = conn.request(
            "POST", "/pair-setup", contentType = OCTET, headers = headers,
            body = Tlv8.encode(
                Tlv8.STATE to Tlv8.byte(3),
                Tlv8.PUBLIC_KEY to srp.publicKey,
                Tlv8.PROOF to proof,
            ),
        ).requireOk("pair-setup M3")
        val m4Tlv = Tlv8.decode(m4.body)
        m4Tlv[Tlv8.ERROR]?.let { throw PairingException(it.firstOrNull()?.toInt() ?: -1) }
        val serverProof = m4Tlv[Tlv8.PROOF] ?: throw PairingException(-1)
        if (!srp.verifyServer(serverProof)) throw PairingException(2)
        return srp.sessionKey
    }

    private fun openEventChannel(port: Int, secret: ByteArray) {
        val s = Socket(device.host, port)
        eventSocket = s
        // Roles are reversed on the event channel: the receiver is the one sending requests.
        val input = DecryptedInputStream(s.getInputStream(), Crypto.hkdf(secret, "Events-Salt", "Events-Write-Encryption-Key")).buffered()
        val output = EncryptedOutputStream(s.getOutputStream(), Crypto.hkdf(secret, "Events-Salt", "Events-Read-Encryption-Key"))
        scope.launch {
            try {
                val line = StringBuilder()
                var cseq = "0"
                var contentLength = 0
                while (isActive) {
                    val c = input.read()
                    if (c < 0) break
                    if (c == '\n'.code) {
                        val l = line.toString().trim()
                        line.clear()
                        if (l.startsWith("CSeq:", true)) cseq = l.substringAfter(':').trim()
                        if (l.startsWith("Content-Length:", true)) contentLength = l.substringAfter(':').trim().toIntOrNull() ?: 0
                        if (l.isEmpty()) {
                            repeat(contentLength) { input.read() }
                            output.write("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n".toByteArray())
                            output.flush()
                            contentLength = 0
                        }
                    } else if (c != '\r'.code) line.append(c.toChar())
                }
            } catch (_: Exception) {
            }
        }
    }

    // ---- Audio -------------------------------------------------------------------------

    override fun write(pcm: ByteArray, length: Int) {
        if (_state.value != SinkState.Streaming) return
        var offset = 0
        while (offset < length) {
            val n = minOf(pending.size - pendingLen, length - offset)
            System.arraycopy(pcm, offset, pending, pendingLen, n)
            pendingLen += n
            offset += n
            if (pendingLen == pending.size) {
                sendPacket(Alac.encode(pending, 0, Alac.FRAMES_PER_PACKET))
                pendingLen = 0
            }
        }
    }

    @Synchronized
    private fun sendPacket(alac: ByteArray) {
        if (firstPacket) sendSync(first = true)

        val header = ByteBuffer.allocate(12)
            .put(0x80.toByte())
            .put((if (firstPacket) 0xE0 else 0x60).toByte())
            .putShort(seq.toShort())
            .putInt(rtpTime)
            .putInt(ssrc)
            .array()
        val nonce = Crypto.nonce(packetCounter++)
        val sealed = Crypto.seal(audioKey, nonce, header.copyOfRange(4, 12), alac)
        val packet = header + sealed + nonce.copyOfRange(4, 12)

        history[seq and 1023] = packet
        try {
            dataSocket.send(DatagramPacket(packet, packet.size, remote, remoteDataPort))
        } catch (e: Exception) {
            Log.w(TAG, "RTP send failed", e)
        }

        firstPacket = false
        seq = (seq + 1) and 0xFFFF
        rtpTime += Alac.FRAMES_PER_PACKET
        realignClock()
    }

    /**
     * Capture runs on the audio HAL's clock while sync packets use the system clock. If they
     * drift apart by more than a few packets, re-anchor so the receiver's buffer never runs dry.
     */
    private fun realignClock() {
        val elapsedFrames = ((System.nanoTime() - anchorNanos) * AudioSink.SAMPLE_RATE / 1_000_000_000L).toInt()
        val drift = (rtpTime - anchorRtp) - elapsedFrames
        if (kotlin.math.abs(drift) > AudioSink.SAMPLE_RATE / 5) {
            anchorNanos = System.nanoTime()
            anchorRtp = rtpTime
        }
    }

    private fun sendSync(first: Boolean) {
        val elapsed = ((System.nanoTime() - anchorNanos) * AudioSink.SAMPLE_RATE / 1_000_000_000L).toInt()
        val nowRtp = anchorRtp + elapsed
        val buf = ByteBuffer.allocate(20)
            .put((if (first) 0x90 else 0x80).toByte())
            .put(0xD4.toByte())
            .putShort(0x0007)
            .putInt(nowRtp - latencyFrames)
            .putLong(ntpNow())
            .putInt(nowRtp)
            .array()
        try {
            controlSocket.send(DatagramPacket(buf, buf.size, remote, remoteControlPort))
        } catch (_: Exception) {
        }
    }

    private suspend fun syncLoop() {
        while (scope.isActive) {
            delay(1000)
            if (!firstPacket) synchronized(this) { sendSync(first = false) }
        }
    }

    private suspend fun feedbackLoop() {
        while (scope.isActive) {
            delay(2000)
            try {
                rtsp?.request("POST", "/feedback")
            } catch (e: Exception) {
                Log.w(TAG, "Lost connection to ${device.name}", e)
                _state.value = SinkState.Failed("Lost connection to ${device.name}")
                teardown()
                return
            }
        }
    }

    /** Answers the receiver's NTP-style timing requests so it can model our clock. */
    private fun timingResponder() {
        val buf = ByteArray(128)
        timingSocket.soTimeout = 1000
        while (scope.isActive) {
            val p = DatagramPacket(buf, buf.size)
            try {
                timingSocket.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            if (p.length < 32 || buf[1].toInt() and 0x7F != 0x52) continue
            val received = ntpNow()
            val reply = ByteBuffer.allocate(32)
                .put(0x80.toByte()).put(0xD3.toByte()).putShort(0x0007)
                .putInt(0)
                .put(buf, 24, 8)     // originate = their transmit time
                .putLong(received)
                .putLong(ntpNow())
                .array()
            runCatching { timingSocket.send(DatagramPacket(reply, reply.size, p.address, p.port)) }
        }
    }

    /** Resends packets the receiver reports as lost. */
    private fun controlReceiver() {
        val buf = ByteArray(64)
        controlSocket.soTimeout = 1000
        while (scope.isActive) {
            val p = DatagramPacket(buf, buf.size)
            try {
                controlSocket.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            if (p.length < 8 || buf[1].toInt() and 0x7F != 0x55) continue
            val first = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
            val count = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
            for (i in 0 until minOf(count, 512)) {
                val s = (first + i) and 0xFFFF
                val original = history[s and 1023] ?: continue
                val origSeq = ((original[2].toInt() and 0xFF) shl 8) or (original[3].toInt() and 0xFF)
                if (origSeq != s) continue
                val resend = byteArrayOf(0x80.toByte(), 0xD6.toByte(), (s shr 8).toByte(), s.toByte()) + original
                runCatching { controlSocket.send(DatagramPacket(resend, resend.size, remote, remoteControlPort)) }
            }
        }
    }

    // ---- Volume & teardown ------------------------------------------------------------

    override fun setVolume(value: Float) {
        _volume.value = value.coerceIn(0f, 1f)
        if (_state.value == SinkState.Streaming) scope.launch { runCatching { sendVolume(_volume.value) } }
    }

    private fun sendVolume(v: Float) {
        // AirPlay volume is in dB from -30 (quietest) to 0, with -144 meaning muted.
        val db = if (v <= 0.001f) -144f else -30f + 30f * v
        rtsp?.request(
            "SET_PARAMETER", sessionUri,
            body = "volume: %.6f\r\n".format(java.util.Locale.US, db).toByteArray(),
            contentType = "text/parameters",
        )
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        runCatching { rtsp?.request("TEARDOWN", sessionUri) }
        teardown()
        _state.value = SinkState.Stopped
    }

    private fun teardown() {
        scope.cancel()
        rtsp?.close()
        runCatching { eventSocket?.close() }
        if (::timingSocket.isInitialized) timingSocket.close()
        if (::controlSocket.isInitialized) controlSocket.close()
        if (::dataSocket.isInitialized) dataSocket.close()
    }

    private fun ntpNow(): Long {
        val seconds = ntpBaseSeconds + (System.nanoTime() - ntpBaseNanos) / 1e9
        val whole = seconds.toLong()
        val fraction = ((seconds - whole) * 4294967296.0).toLong()
        return (whole shl 32) or (fraction and 0xFFFFFFFFL)
    }

    private fun RtspResponse.requireOk(step: String): RtspResponse {
        if (!ok) throw RtspException(status, "$step failed ($status)")
        return this
    }

    private fun explain(e: Exception): String = when {
        e is PairingException || (e is RtspException && e.status in setOf(403, 470)) ->
            if (device.kind == AirPlayKind.HOMEPOD || device.kind == AirPlayKind.HOMEPOD_MINI)
                "HomePod refused the connection. In the Home app, open Home Settings → Speakers & TV and set access to “Everyone” or “Anyone on the same network”, with no password."
            else "${device.name} refused the connection. Turn off any AirPlay password or access restriction on it."
        e is java.net.ConnectException || e is java.net.SocketTimeoutException ->
            "Couldn't reach ${device.name}. Make sure your phone is on the same Wi-Fi."
        else -> e.message ?: "Couldn't connect to ${device.name}"
    }

    class PairingException(val code: Int) : Exception("Pairing rejected (TLV error $code)")

    companion object {
        private const val TAG = "AirPlaySink"
        private const val PLIST = "application/x-apple-binary-plist"
        private const val OCTET = "application/octet-stream"
        private const val NTP_EPOCH_OFFSET = 2208988800.0
    }
}
