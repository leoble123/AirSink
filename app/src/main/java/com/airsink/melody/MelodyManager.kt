package com.airsink.melody

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.airsink.core.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Finds connected OnePlus / OPPO / realme earbuds and keeps a control channel open to them.
 * Unlike AirPods, these use a normal RFCOMM service, so no hidden APIs are needed.
 */
class MelodyManager(private val context: Context, private val scope: CoroutineScope) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow<MelodyState?>(null)
    /** Null when no supported earbuds are connected. */
    val state: StateFlow<MelodyState?> = _state.asStateFlow()

    private var device: BluetoothDevice? = null
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var job: Job? = null
    private var seq = 0
    private var started = false

    fun start() {
        if (started || !Permissions.hasBluetooth(context)) return
        started = true
        val filter = IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        bindA2dp()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val d = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            if (!isSupported(d)) return
            when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                BluetoothProfile.STATE_CONNECTED -> attach(d)
                BluetoothProfile.STATE_DISCONNECTED -> if (d.address == device?.address) detach()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bindA2dp() {
        adapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                proxy.connectedDevices.firstOrNull(::isSupported)?.let(::attach)
                adapter?.closeProfileProxy(profile, proxy)
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
    }

    // ---- Connection ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun attach(d: BluetoothDevice) {
        if (device?.address == d.address && _state.value?.connection == MelodyState.Connection.CONNECTED) return
        detach()
        device = d
        val name = safeName(d) ?: "Earbuds"
        _state.value = MelodyState(
            connection = MelodyState.Connection.CONNECTING,
            name = name,
            address = d.address,
            brand = brandFor(name),
        )
        job = scope.launch(Dispatchers.IO) {
            delay(1200) // let the audio profiles finish connecting first
            try {
                adapter?.cancelDiscovery()
                val s = openSocket(d, brandFor(name))
                socket = s
                output = s.outputStream
                _state.update { it?.copy(connection = MelodyState.Connection.CONNECTED, error = null) }
                handshake()
                readLoop(s)
            } catch (e: Exception) {
                Log.w(TAG, "Control channel failed", e)
                _state.update { it?.copy(connection = MelodyState.Connection.FAILED, error = e.message) }
            } finally {
                closeSocket()
                _state.update {
                    if (it?.connection == MelodyState.Connection.CONNECTED) it.copy(connection = MelodyState.Connection.DISCONNECTED) else it
                }
            }
        }
    }

    private fun detach() {
        job?.cancel()
        closeSocket()
        device = null
        _state.value = null
    }

    fun reconnect() {
        device?.let { d -> job?.cancel(); closeSocket(); attach(d) }
    }

    /** OnePlus buds answer on their own UUID first; everything falls back to the shared one. */
    @SuppressLint("MissingPermission")
    private fun openSocket(d: BluetoothDevice, brand: MelodyState.Brand): BluetoothSocket {
        val uuids = if (brand == MelodyState.Brand.ONEPLUS)
            listOf(MelodyProtocol.SERVICE_UUID_ONEPLUS, MelodyProtocol.SERVICE_UUID)
        else listOf(MelodyProtocol.SERVICE_UUID, MelodyProtocol.SERVICE_UUID_ONEPLUS)
        var last: IOException? = null
        for (u in uuids) {
            val s = d.createRfcommSocketToServiceRecord(UUID.fromString(u))
            try {
                s.connect()
                return s
            } catch (e: IOException) {
                last = e
                try { s.close() } catch (_: IOException) {}
            }
        }
        throw last ?: IOException("No control channel")
    }

    private fun handshake() {
        val brand = _state.value?.brand ?: MelodyState.Brand.ONEPLUS
        send(MelodyProtocol.Cmd.SUBSCRIBE, MelodyProtocol.subscribe(brand))
        send(MelodyProtocol.Cmd.ANC_REQ, MelodyProtocol.ancModeReq())
        send(MelodyProtocol.Cmd.ANC_REQ, MelodyProtocol.ancCycleReq())
        send(
            MelodyProtocol.Cmd.MISC_REQ,
            MelodyProtocol.miscReq(
                MelodyProtocol.Misc.LDAC, MelodyProtocol.Misc.MULTIPOINT, MelodyProtocol.Misc.GAME_MODE, MelodyProtocol.Misc.AUTO_PAUSE,
            ),
        )
        send(MelodyProtocol.Cmd.WEAR_REQ)
        send(MelodyProtocol.Cmd.TOUCH_REQ, MelodyProtocol.touchReq())
        send(MelodyProtocol.Cmd.FIRMWARE_REQ)
        send(MelodyProtocol.Cmd.BATTERY_REQ)
        // Some models ignore the first battery request.
        scope.launch(Dispatchers.IO) {
            repeat(3) {
                delay(2000)
                if (_state.value?.left != null || _state.value?.right != null) return@launch
                send(MelodyProtocol.Cmd.BATTERY_REQ)
            }
        }
    }

    private fun readLoop(s: BluetoothSocket) {
        val input = s.inputStream
        val pending = ByteArrayOutputStream()
        val buf = ByteArray(512)
        while (scope.isActive) {
            val n = input.read(buf)
            if (n < 0) break
            pending.write(buf, 0, n)
            for (frame in MelodyProtocol.drainFrames(pending)) {
                _state.update { it?.let { st -> MelodyProtocol.apply(st, frame) } }
            }
        }
    }

    private fun send(command: Int, payload: ByteArray = ByteArray(0)) {
        val out = output ?: return
        val packet = synchronized(this) { MelodyProtocol.encode(command, seq++ and 0xFF, payload) }
        scope.launch(Dispatchers.IO) {
            try {
                synchronized(out) { out.write(packet); out.flush() }
            } catch (e: IOException) {
                Log.w(TAG, "write failed", e)
            }
        }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        output = null
    }

    // ---- Commands --------------------------------------------------------------------

    fun setAnc(mode: MelodyAnc, level: AncLevel? = null) {
        val brand = _state.value?.brand ?: return
        _state.update { it?.copy(anc = mode, ancLevel = level ?: it.ancLevel) }
        send(MelodyProtocol.Cmd.ANC_SET, MelodyProtocol.ancModeSet(mode, brand, level))
    }

    fun setCycleMask(mask: Int) {
        val brand = _state.value?.brand ?: return
        _state.update { it?.copy(cycleMask = mask) }
        send(MelodyProtocol.Cmd.ANC_SET, MelodyProtocol.ancCycleSet(mask, brand))
    }

    fun setLdac(on: Boolean) = setMisc(MelodyProtocol.Misc.LDAC, on) { copy(ldac = on) }
    fun setMultipoint(on: Boolean) = setMisc(MelodyProtocol.Misc.MULTIPOINT, on) { copy(multipoint = on) }
    fun setGameMode(on: Boolean) = setMisc(MelodyProtocol.Misc.GAME_MODE, on) { copy(gameMode = on) }
    fun setAutoPause(on: Boolean) = setMisc(MelodyProtocol.Misc.AUTO_PAUSE, on) { copy(autoPause = on) }

    private fun setMisc(type: Int, on: Boolean, update: MelodyState.() -> MelodyState) {
        _state.update { it?.update() }
        send(MelodyProtocol.Cmd.MISC_SET, MelodyProtocol.miscSet(type, on))
    }

    fun setTouch(side: TouchSide, gesture: Gesture, action: Int) {
        _state.update { it?.copy(touch = it.touch + ((side to gesture) to action)) }
        send(MelodyProtocol.Cmd.TOUCH_SET, MelodyProtocol.touchSet(side, gesture, action))
    }

    fun ring(on: Boolean) {
        _state.update { it?.copy(ringing = on) }
        send(MelodyProtocol.Cmd.FIND_DEVICE, MelodyProtocol.findDevice(on))
        if (on) scope.launch {
            delay(30_000) // don't let them beep forever if the user walks away
            if (_state.value?.ringing == true) ring(false)
        }
    }

    // ---- Detection -------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun isSupported(d: BluetoothDevice): Boolean {
        val uuids = try { d.uuids } catch (_: SecurityException) { null }
        val ours = setOf(MelodyProtocol.SERVICE_UUID, MelodyProtocol.SERVICE_UUID_ONEPLUS)
        if (uuids?.any { u -> ours.any { it.equals(u.uuid.toString(), true) } } == true) return true
        return NAME_PATTERN.containsMatchIn(safeName(d).orEmpty())
    }

    @SuppressLint("MissingPermission")
    private fun safeName(d: BluetoothDevice): String? = try {
        (if (android.os.Build.VERSION.SDK_INT >= 30) d.alias else null) ?: d.name
    } catch (_: SecurityException) { null }

    companion object {
        private const val TAG = "MelodyManager"
        private val NAME_PATTERN = Regex("(?i)(oneplus buds|nord buds|oppo enco|realme buds)")

        fun brandFor(name: String) = when {
            name.contains("realme", true) -> MelodyState.Brand.REALME
            name.contains("oppo", true) || name.contains("enco", true) -> MelodyState.Brand.OPPO
            else -> MelodyState.Brand.ONEPLUS
        }
    }
}
