package com.airsink.airpods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.airsink.core.Permissions
import com.airsink.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Everything the UI needs to know about the user's Apple headphones, merged from all sources. */
data class HeadphonesState(
    val name: String,
    val address: String?,
    val model: AppleModel,
    val connected: Boolean,
    val left: Battery,
    val right: Battery,
    val case: Battery,
    val headset: Battery,
    val leftInEar: Boolean,
    val rightInEar: Boolean,
    val lidOpen: Boolean,
    val nearby: Boolean,
    val aap: AapState,
) {
    val controlsAvailable get() = aap.connection == AapState.Connection.CONNECTED
    val anyInEar get() = leftInEar || rightInEar
}

class AirPodsManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
) {
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetooth?.adapter
    private val audio = context.getSystemService(AudioManager::class.java)

    val aap = AapClient(scope)

    private val latestBle = MutableStateFlow<ProximityMessage?>(null)
    private var latestBleAt = 0L
    private val connectedDevice = MutableStateFlow<BluetoothDevice?>(null)

    private val _popups = MutableSharedFlow<ProximityMessage>(extraBufferCapacity = 1)
    /** Fires when a nearby case is opened, used to show the iOS-style connection card. */
    val popups: SharedFlow<ProximityMessage> = _popups
    private var lastPopupAt = 0L
    private var lastLidOpen = false

    private val _state = MutableStateFlow<HeadphonesState?>(null)
    val state: StateFlow<HeadphonesState?> = _state.asStateFlow()

    private var a2dp: BluetoothA2dp? = null
    private var scanning = false
    private var staleJob: Job? = null

    private var started = false

    fun start() {
        if (started) return
        started = true
        registerReceivers()
        bindA2dp()
        scope.launch {
            combine(latestBle, connectedDevice, aap.state) { ble, device, aapState -> merge(ble, device, aapState) }
                .collect { _state.value = it }
        }
        scope.launch { watchEarDetection() }
        scope.launch { watchConversationalAwareness() }
        scope.launch {
            connectedDevice.collectLatest { device ->
                if (device != null && prefs.advancedControls.value) {
                    delay(1500) // let the audio profiles settle first
                    aap.connect(device)
                } else if (device == null) {
                    aap.disconnect()
                }
            }
        }
        // Forget BLE data that has gone quiet so we don't show a stale battery forever.
        staleJob = scope.launch {
            while (true) {
                delay(5_000)
                if (latestBle.value != null && SystemClock.elapsedRealtime() - latestBleAt > 30_000) {
                    latestBle.value = null
                }
            }
        }
        startScan(lowLatency = false)
    }

    // ---- BLE ---------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            scanning = false
        }
    }

    private fun handle(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(ProximityParser.APPLE_COMPANY_ID)
        val msg = ProximityParser.parse(data, result.rssi) ?: return
        // Ignore other people's AirPods: only accept strong signals, or ones matching the
        // model we're already tracking.
        val current = latestBle.value
        val fresh = SystemClock.elapsedRealtime() - latestBleAt < 10_000
        val accept = result.rssi >= prefs.rssiThreshold.value ||
            (current != null && fresh && current.model.id == msg.model.id && result.rssi >= current.rssi - 10)
        if (!accept) return
        latestBle.value = msg
        latestBleAt = SystemClock.elapsedRealtime()

        val now = SystemClock.elapsedRealtime()
        if (msg.lidOpen && !lastLidOpen && msg.model.hasCase && now - lastPopupAt > 20_000) {
            lastPopupAt = now
            _popups.tryEmit(msg)
        }
        lastLidOpen = msg.lidOpen
    }

    @SuppressLint("MissingPermission")
    fun startScan(lowLatency: Boolean) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (!Permissions.hasBluetooth(context)) return
        if (scanning) stopScan()
        val filter = ScanFilter.Builder()
            .setManufacturerData(
                ProximityParser.APPLE_COMPANY_ID,
                byteArrayOf(0x07, 0x19),
                byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
            )
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(if (lowLatency) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing scan permission", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
    }

    // ---- Classic Bluetooth connection tracking ----------------------------------------

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            when (intent.action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val st = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (device != null && isApple(device)) {
                        if (st == BluetoothProfile.STATE_CONNECTED) connectedDevice.value = device
                        else if (st == BluetoothProfile.STATE_DISCONNECTED &&
                            connectedDevice.value?.address == device.address
                        ) connectedDevice.value = null
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (st == BluetoothAdapter.STATE_ON) { bindA2dp(); startScan(false) }
                    if (st == BluetoothAdapter.STATE_OFF) { connectedDevice.value = null; scanning = false }
                }
            }
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    @SuppressLint("MissingPermission")
    fun bindA2dp() {
        if (!Permissions.hasBluetooth(context)) return
        adapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                a2dp = proxy as BluetoothA2dp
                connectedDevice.value = proxy.connectedDevices.firstOrNull(::isApple)
            }
            override fun onServiceDisconnected(profile: Int) { a2dp = null }
        }, BluetoothProfile.A2DP)
    }

    @SuppressLint("MissingPermission")
    fun bondedAppleDevices(): List<BluetoothDevice> =
        if (!Permissions.hasBluetooth(context)) emptyList()
        else adapter?.bondedDevices?.filter(::isApple).orEmpty()

    /**
     * Tries to bring up the audio connection to already-paired headphones. Regular apps
     * aren't allowed to call A2DP connect on modern Android, so if the hidden call is
     * rejected we return false and the UI opens the system Bluetooth panel instead.
     */
    @SuppressLint("MissingPermission")
    fun tryConnect(device: BluetoothDevice): Boolean {
        val proxy = a2dp ?: return false
        return try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/")
            val m = BluetoothA2dp::class.java.getDeclaredMethod("connect", BluetoothDevice::class.java)
            (m.invoke(proxy, device) as? Boolean) == true
        } catch (e: Throwable) {
            Log.i(TAG, "A2DP connect not permitted: ${e.cause ?: e}")
            false
        }
    }

    fun reconnectControls() {
        connectedDevice.value?.let { aap.disconnect(); aap.connect(it) }
    }

    @SuppressLint("MissingPermission")
    private fun isApple(device: BluetoothDevice): Boolean {
        val uuids: Array<ParcelUuid>? = try { device.uuids } catch (_: SecurityException) { null }
        if (uuids?.any { it.uuid.toString().equals(AAP_SERVICE_UUID, ignoreCase = true) } == true) return true
        val name = try { device.name } catch (_: SecurityException) { null }
        return AppleModels.guessFromName(name) != null
    }

    // ---- State merging -----------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun merge(ble: ProximityMessage?, device: BluetoothDevice?, aapState: AapState): HeadphonesState? {
        if (ble == null && device == null) return null
        val name = device?.let { try { (if (android.os.Build.VERSION.SDK_INT >= 30) it.alias else null) ?: it.name } catch (_: SecurityException) { null } }
        val model = ble?.model ?: AppleModels.guessFromName(name) ?: AppleModels.forId(0)
        val aapLive = aapState.connection == AapState.Connection.CONNECTED

        // AAP gives exact percentages; BLE only 10% steps, so prefer AAP when we have it.
        fun pick(aapValue: Battery, bleValue: Battery?) =
            if (aapLive && aapValue.known) aapValue else bleValue ?: Battery.Unknown

        val left = pick(aapState.left, ble?.left)
        val right = pick(aapState.right, ble?.right)
        val headset = if (aapLive && aapState.single.known) aapState.single
        else ble?.headset ?: listOf(left, right).firstOrNull { it.known } ?: Battery.Unknown

        return HeadphonesState(
            name = name ?: model.name,
            address = device?.address,
            model = model,
            connected = device != null,
            left = left,
            right = right,
            case = pick(aapState.case, ble?.case),
            headset = headset,
            // AAP reports ear changes instantly; BLE broadcasts can lag a few seconds in the background.
            leftInEar = if (aapLive && aapState.primaryEar != EarState.UNKNOWN) aapState.primaryEar == EarState.IN_EAR else ble?.leftInEar ?: false,
            rightInEar = if (aapLive && aapState.secondaryEar != EarState.UNKNOWN) aapState.secondaryEar == EarState.IN_EAR else ble?.rightInEar ?: false,
            lidOpen = ble?.lidOpen ?: false,
            nearby = ble != null,
            aap = aapState,
        )
    }

    // ---- Automatic ear detection ------------------------------------------------------

    private var pausedByEarDetection = false

    private suspend fun watchEarDetection() {
        state.map { s -> s?.takeIf { it.connected }?.let { it.leftInEar to it.rightInEar } }
            .distinctUntilChanged()
            .collect { ears ->
                if (ears == null || !prefs.earDetection.value) return@collect
                val inEar = (if (ears.first) 1 else 0) + (if (ears.second) 1 else 0)
                onInEarCount(inEar)
            }
    }

    private var lastInEar = -1

    private fun onInEarCount(count: Int) {
        val previous = lastInEar
        lastInEar = count
        if (previous < 0) return
        if (count < previous && audio.isMusicActive) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            pausedByEarDetection = true
        } else if (count > previous && count >= 1 && pausedByEarDetection) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            pausedByEarDetection = false
        }
    }

    private fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ---- Conversational awareness: duck media while the wearer talks -----------------

    private suspend fun watchConversationalAwareness() {
        var restoreTo: Int? = null
        aap.state.map { it.userSpeaking }.distinctUntilChanged().collect { speaking ->
            if (!prefs.conversationDucking.value) return@collect
            if (speaking && restoreTo == null) {
                val v = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                restoreTo = v
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * 0.3f).toInt().coerceAtLeast(1), 0)
            } else if (!speaking) {
                restoreTo?.let { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
                restoreTo = null
            }
        }
    }

    companion object {
        private const val TAG = "AirPodsManager"
        const val AAP_SERVICE_UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a"
    }
}
