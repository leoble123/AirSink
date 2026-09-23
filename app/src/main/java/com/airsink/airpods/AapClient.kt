package com.airsink.airpods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

enum class NoiseMode(val wire: Int, val label: String) {
    OFF(0x01, "Off"),
    ANC(0x02, "Noise Cancellation"),
    TRANSPARENCY(0x03, "Transparency"),
    ADAPTIVE(0x04, "Adaptive");

    companion object {
        fun fromWire(v: Int) = entries.firstOrNull { it.wire == v }
    }
}

enum class EarState { IN_EAR, OUT_OF_EAR, IN_CASE, UNKNOWN }

data class AapState(
    val connection: Connection = Connection.DISCONNECTED,
    val error: String? = null,
    val left: Battery = Battery.Unknown,
    val right: Battery = Battery.Unknown,
    val case: Battery = Battery.Unknown,
    val single: Battery = Battery.Unknown,
    val primaryEar: EarState = EarState.UNKNOWN,
    val secondaryEar: EarState = EarState.UNKNOWN,
    val noiseMode: NoiseMode? = null,
    /** Bitmask of modes a long-press cycles through (0x01 off, 0x02 ANC, 0x04 transparency, 0x08 adaptive). */
    val cycleModes: Int? = null,
    val conversationalAwareness: Boolean? = null,
    val personalizedVolume: Boolean? = null,
    val oneBudAnc: Boolean? = null,
    val volumeSwipe: Boolean? = null,
    val allowOffOption: Boolean? = null,
    /** 0..100, how aggressively Adaptive mode lets sound in. */
    val adaptiveStrength: Int? = null,
    val userSpeaking: Boolean = false,
) {
    enum class Connection { DISCONNECTED, CONNECTING, CONNECTED, UNSUPPORTED }
}

/**
 * Client for the Apple Accessory Protocol that AirPods speak over a classic Bluetooth
 * L2CAP channel (PSM 0x1001). Packet formats follow the LibrePods reverse-engineering.
 *
 * Android has no public API for classic L2CAP sockets, so we build one through the hidden
 * [BluetoothSocket] constructor. Some Android builds reject the connection in the Bluetooth
 * stack itself; when that happens the state becomes [AapState.Connection.UNSUPPORTED] and
 * the app falls back to the read-only BLE data.
 */
class AapClient(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(AapState())
    val state: StateFlow<AapState> = _state.asStateFlow()

    private val _earEvents = MutableSharedFlow<Pair<EarState, EarState>>(extraBufferCapacity = 8)
    /** Emits (primary, secondary) ear states whenever the AirPods report a change. */
    val earEvents: SharedFlow<Pair<EarState, EarState>> = _earEvents

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var readJob: Job? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (_state.value.connection == AapState.Connection.CONNECTING ||
            _state.value.connection == AapState.Connection.CONNECTED
        ) return
        _state.value = AapState(connection = AapState.Connection.CONNECTING)

        readJob = scope.launch(Dispatchers.IO) {
            try {
                val s = createL2capSocket(device)
                s.connect()
                socket = s
                output = s.outputStream
                send(HANDSHAKE)
                send(SET_FEATURES)
                send(REQUEST_NOTIFICATIONS)
                _state.update { it.copy(connection = AapState.Connection.CONNECTED, error = null) }
                readLoop(s.inputStream)
            } catch (e: Throwable) {
                Log.w(TAG, "AAP connection failed", e)
                val unsupported = e is ReflectiveOperationException || e is SecurityException ||
                    e is UnsupportedOperationException
                _state.update {
                    it.copy(
                        connection = if (unsupported) AapState.Connection.UNSUPPORTED else AapState.Connection.DISCONNECTED,
                        error = e.message ?: e.javaClass.simpleName,
                    )
                }
            } finally {
                closeQuietly()
                if (_state.value.connection == AapState.Connection.CONNECTED) {
                    _state.update { it.copy(connection = AapState.Connection.DISCONNECTED) }
                }
            }
        }
    }

    fun disconnect() {
        readJob?.cancel()
        closeQuietly()
        _state.value = AapState()
    }

    // ---- Commands ----------------------------------------------------------------------

    fun setNoiseMode(mode: NoiseMode) {
        _state.update { it.copy(noiseMode = mode) }
        sendControl(CMD_LISTENING_MODE, mode.wire)
    }

    fun setCycleModes(mask: Int) {
        _state.update { it.copy(cycleModes = mask) }
        sendControl(CMD_LISTENING_MODE_CONFIGS, mask)
    }

    fun setConversationalAwareness(enabled: Boolean) {
        _state.update { it.copy(conversationalAwareness = enabled) }
        sendControl(CMD_CONVERSATION_DETECT, enabled.wire())
    }

    fun setPersonalizedVolume(enabled: Boolean) {
        _state.update { it.copy(personalizedVolume = enabled) }
        sendControl(CMD_ADAPTIVE_VOLUME, enabled.wire())
    }

    fun setOneBudAnc(enabled: Boolean) {
        _state.update { it.copy(oneBudAnc = enabled) }
        sendControl(CMD_ONE_BUD_ANC, enabled.wire())
    }

    fun setVolumeSwipe(enabled: Boolean) {
        _state.update { it.copy(volumeSwipe = enabled) }
        sendControl(CMD_VOLUME_SWIPE, enabled.wire())
    }

    fun setAllowOffOption(enabled: Boolean) {
        _state.update { it.copy(allowOffOption = enabled) }
        sendControl(CMD_ALLOW_OFF_OPTION, enabled.wire())
    }

    fun setAdaptiveStrength(value: Int) {
        val v = value.coerceIn(0, 100)
        _state.update { it.copy(adaptiveStrength = v) }
        sendControl(CMD_AUTO_ANC_STRENGTH, v)
    }

    fun rename(name: String) {
        val bytes = name.toByteArray(Charsets.UTF_8).take(32).toByteArray()
        send(byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x1A, 0x00, 0x01, bytes.size.toByte(), 0x00) + bytes)
    }

    private fun sendControl(id: Int, value: Int) {
        send(byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, id.toByte(), value.toByte(), 0x00, 0x00, 0x00))
    }

    private fun send(packet: ByteArray) {
        val out = output ?: return
        scope.launch(Dispatchers.IO) {
            try {
                synchronized(out) { out.write(packet); out.flush() }
            } catch (e: IOException) {
                Log.w(TAG, "AAP write failed", e)
            }
        }
    }

    // ---- Parsing -----------------------------------------------------------------------

    private suspend fun readLoop(input: InputStream) {
        val buf = ByteArray(1024)
        while (scope.isActive) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) handlePacket(buf.copyOf(n))
        }
    }

    internal fun handlePacket(p: ByteArray) {
        if (p.size < 6 || p[0].toInt() != 0x04 || p[2].toInt() != 0x04) return
        when (p[4].u()) {
            PKT_BATTERY -> parseBattery(p)
            PKT_EAR_DETECTION -> if (p.size >= 8) {
                val primary = ear(p[6].u())
                val secondary = ear(p[7].u())
                _state.update { it.copy(primaryEar = primary, secondaryEar = secondary) }
                _earEvents.tryEmit(primary to secondary)
            }
            PKT_CONTROL -> if (p.size >= 8) parseControl(p[6].u(), p[7].u())
            PKT_CONVERSATION_LEVEL -> if (p.size >= 10) {
                // Levels 1-2 mean the wearer started talking, 8-9 that they stopped.
                val level = p[9].u()
                _state.update { it.copy(userSpeaking = level in 1..2) }
            }
        }
    }

    private fun parseBattery(p: ByteArray) {
        val count = p.getOrNull(6)?.u() ?: return
        var s = _state.value
        for (i in 0 until count) {
            val o = 7 + i * 5
            if (o + 3 >= p.size) break
            val level = p[o + 2].u()
            val status = p[o + 3].u()
            val battery = if (status == 0x04) Battery.Unknown else Battery(level.coerceIn(0, 100), status == 0x01)
            s = when (p[o].u()) {
                0x01 -> s.copy(single = battery)
                0x02 -> s.copy(right = battery)
                0x04 -> s.copy(left = battery)
                0x08 -> s.copy(case = battery)
                else -> s
            }
        }
        _state.value = s
    }

    private fun parseControl(id: Int, v: Int) {
        _state.update {
            when (id) {
                CMD_LISTENING_MODE -> it.copy(noiseMode = NoiseMode.fromWire(v) ?: it.noiseMode)
                CMD_LISTENING_MODE_CONFIGS -> it.copy(cycleModes = v)
                CMD_CONVERSATION_DETECT -> it.copy(conversationalAwareness = v == 0x01)
                CMD_ADAPTIVE_VOLUME -> it.copy(personalizedVolume = v == 0x01)
                CMD_ONE_BUD_ANC -> it.copy(oneBudAnc = v == 0x01)
                CMD_VOLUME_SWIPE -> it.copy(volumeSwipe = v == 0x01)
                CMD_ALLOW_OFF_OPTION -> it.copy(allowOffOption = v == 0x01)
                CMD_AUTO_ANC_STRENGTH -> it.copy(adaptiveStrength = v)
                else -> it
            }
        }
    }

    // ---- Socket plumbing ---------------------------------------------------------------

    private fun createL2capSocket(device: BluetoothDevice): BluetoothSocket {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/BluetoothSocket;")
        }
        val uuid = ParcelUuid.fromString(AAP_UUID)
        val bool = java.lang.Boolean.TYPE
        val int = Integer.TYPE
        val candidates: List<Pair<Array<Class<*>>, Array<Any?>>> = listOf(
            // Android 12+
            arrayOf<Class<*>>(BluetoothDevice::class.java, int, bool, bool, int, ParcelUuid::class.java) to
                arrayOf(device, TYPE_L2CAP, true, true, PSM_AAP, uuid),
            arrayOf<Class<*>>(BluetoothDevice::class.java, int, bool, bool, int, ParcelUuid::class.java, bool, bool) to
                arrayOf(device, TYPE_L2CAP, true, true, PSM_AAP, uuid, false, false),
            // Android 10-11
            arrayOf<Class<*>>(int, int, bool, bool, BluetoothDevice::class.java, int, ParcelUuid::class.java) to
                arrayOf(TYPE_L2CAP, -1, true, true, device, PSM_AAP, uuid),
        )
        var last: Throwable? = null
        for ((types, args) in candidates) {
            try {
                val ctor = BluetoothSocket::class.java.getDeclaredConstructor(*types)
                ctor.isAccessible = true
                return ctor.newInstance(*args) as BluetoothSocket
            } catch (e: ReflectiveOperationException) {
                last = e
            }
        }
        throw UnsupportedOperationException("No usable L2CAP socket constructor on this Android build", last)
    }

    private fun closeQuietly() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        output = null
    }

    private fun ear(v: Int) = when (v) {
        0x00 -> EarState.IN_EAR
        0x01 -> EarState.OUT_OF_EAR
        0x02 -> EarState.IN_CASE
        else -> EarState.UNKNOWN
    }

    private fun Boolean.wire() = if (this) 0x01 else 0x02
    private fun Byte.u() = toInt() and 0xFF

    companion object {
        private const val TAG = "AapClient"
        private const val TYPE_L2CAP = 3
        private const val PSM_AAP = 0x1001
        private const val AAP_UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a"

        private const val PKT_BATTERY = 0x04
        private const val PKT_EAR_DETECTION = 0x06
        private const val PKT_CONTROL = 0x09
        private const val PKT_CONVERSATION_LEVEL = 0x4B

        const val CMD_LISTENING_MODE = 0x0D
        const val CMD_LISTENING_MODE_CONFIGS = 0x1A
        const val CMD_ONE_BUD_ANC = 0x1B
        const val CMD_VOLUME_SWIPE = 0x25
        const val CMD_ADAPTIVE_VOLUME = 0x26
        const val CMD_CONVERSATION_DETECT = 0x28
        const val CMD_AUTO_ANC_STRENGTH = 0x2E
        const val CMD_ALLOW_OFF_OPTION = 0x34

        private val HANDSHAKE = bytes(0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        private val SET_FEATURES = bytes(0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        private val REQUEST_NOTIFICATIONS = bytes(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00, 0xFF, 0xFF, 0xFF, 0xFF)

        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    }
}
