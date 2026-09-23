package com.airsink.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque

enum class AirPlayKind { HOMEPOD, HOMEPOD_MINI, APPLE_TV, AIRPORT, SONOS, SPEAKER }

data class AirPlayDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val model: String,
    val kind: AirPlayKind,
    val features: Long,
    val requiresPassword: Boolean,
) {
    val supportsAirPlay2 get() = features and (1L shl 38) != 0L || features and (1L shl 48) != 0L
}

/** Browses the local network for AirPlay receivers over mDNS (Bonjour). */
class AirPlayDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val _devices = MutableStateFlow<Map<String, AirPlayDevice>>(emptyMap())
    val devices: StateFlow<Map<String, AirPlayDevice>> = _devices.asStateFlow()

    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode"); listener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                _devices.update { m -> m.filterValues { it.name != info.serviceName } }
            }
        }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
    }

    fun restart() {
        stop()
        _devices.value = emptyMap()
        start()
    }

    // NsdManager can only resolve one service at a time on older Android versions.
    @Synchronized
    private fun enqueue(info: NsdServiceInfo) {
        resolveQueue.add(info)
        if (!resolving) next()
    }

    @Synchronized
    private fun next() {
        val info = resolveQueue.poll() ?: run { resolving = false; return }
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
                next()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                parse(serviceInfo)?.let { d -> _devices.update { it + (d.id to d) } }
                next()
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun parse(info: NsdServiceInfo): AirPlayDevice? {
        val host = info.host?.hostAddress ?: return null
        val txt = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) ?: "" }
        val model = txt["model"] ?: ""
        val manufacturer = txt["manufacturer"] ?: ""
        val kind = when {
            model.startsWith("AudioAccessory5") -> AirPlayKind.HOMEPOD_MINI
            model.startsWith("AudioAccessory") -> AirPlayKind.HOMEPOD
            model.startsWith("AppleTV") -> AirPlayKind.APPLE_TV
            model.startsWith("AirPort") -> AirPlayKind.AIRPORT
            manufacturer.contains("Sonos", ignoreCase = true) || model.contains("Sonos", ignoreCase = true) -> AirPlayKind.SONOS
            else -> AirPlayKind.SPEAKER
        }
        return AirPlayDevice(
            id = txt["deviceid"] ?: txt["pi"] ?: info.serviceName,
            name = info.serviceName,
            host = host,
            port = info.port,
            model = model,
            kind = kind,
            features = parseFeatures(txt["features"] ?: txt["ft"] ?: "0"),
            requiresPassword = txt["pw"] == "true" || txt["pw"] == "1",
        )
    }

    companion object {
        private const val TAG = "AirPlayDiscovery"
        const val SERVICE_TYPE = "_airplay._tcp"

        /** Features are "0xLOW,0xHIGH" (or a single hex value) forming a 64-bit mask. */
        fun parseFeatures(s: String): Long {
            val parts = s.split(",").map { it.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: 0L }
            return (parts.getOrElse(0) { 0L } and 0xFFFFFFFFL) or (parts.getOrElse(1) { 0L } shl 32)
        }
    }
}
