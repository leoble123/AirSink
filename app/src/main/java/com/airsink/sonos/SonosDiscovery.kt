package com.airsink.sonos

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL

/** One Sonos player (a room, or one half of a bonded set). */
data class SonosPlayer(
    val uuid: String,
    val room: String,
    val host: String,
    val model: String,
    val coordinatorUuid: String,
) {
    val isCoordinator get() = uuid == coordinatorUuid
}

/** A group of rooms playing in sync; the coordinator controls transport and streaming. */
data class SonosGroup(val coordinator: SonosPlayer, val members: List<SonosPlayer>) {
    val name: String
        get() = members.map { it.room }.distinct().let { rooms ->
            if (rooms.size <= 1) coordinator.room else "${coordinator.room} + ${rooms.size - 1}"
        }
}

/**
 * Finds Sonos speakers with SSDP, then asks one of them for the household's full zone-group
 * topology so we know every room and how they are grouped.
 */
class SonosDiscovery(private val context: Context, private val scope: CoroutineScope) {
    private val _groups = MutableStateFlow<List<SonosGroup>>(emptyList())
    val groups: StateFlow<List<SonosGroup>> = _groups.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    /** Human-readable detail when speakers were seen but couldn't be read, for the empty state. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val nsd = context.getSystemService(android.net.nsd.NsdManager::class.java)
    private var mdnsListener: android.net.nsd.NsdManager.DiscoveryListener? = null

    private val knownHosts = LinkedHashSet<String>()
    private val models = HashMap<String, String>()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        startMdns()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                search()
                refreshTopology()
                delay(if (knownHosts.isEmpty()) 10_000 else 30_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        mdnsListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        mdnsListener = null
    }

    /** Hosts learned elsewhere (e.g. AirPlay adverts from Sonos speakers). */
    fun addHosts(hosts: Collection<String>) {
        val added = synchronized(knownHosts) { knownHosts.addAll(hosts) }
        if (added) refresh()
    }

    /**
     * Sonos speakers also announce themselves over Bonjour, which gets through on networks
     * where SSDP multicast replies are filtered.
     */
    private fun startMdns() {
        if (mdnsListener != null) return
        val l = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { mdnsListener = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: android.net.nsd.NsdServiceInfo) {}
            override fun onServiceFound(info: android.net.nsd.NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : android.net.nsd.NsdManager.ResolveListener {
                    override fun onResolveFailed(i: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(i: android.net.nsd.NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        i.host?.hostAddress?.let { addHosts(listOf(it)) }
                    }
                })
            }
        }
        mdnsListener = l
        runCatching { nsd.discoverServices("_sonos._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { mdnsListener = null }
    }

    fun refresh() = scope.launch(Dispatchers.IO) { search(); refreshTopology() }

    private fun search() {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("airsink-sonos").apply { setReferenceCounted(false) }
        try {
            lock.acquire()
            MulticastSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(null)
                socket.soTimeout = 1500
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\nST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { socket.send(DatagramPacket(msg, msg.size, group, 1900)) }
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(p)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    val text = String(p.data, 0, p.length)
                    if (text.contains("Sonos", ignoreCase = true)) {
                        synchronized(knownHosts) { knownHosts += p.address.hostAddress!! }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP search failed", e)
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    private suspend fun refreshTopology() {
        val hosts = synchronized(knownHosts) { knownHosts.toList() }
        if (hosts.isEmpty()) {
            _status.value = null
            return
        }
        var lastError: Throwable? = null
        for (host in hosts) {
            val result = runCatching { SonosClient(host).zoneGroupState() }
            val xml = result.getOrNull()
            if (xml == null) {
                lastError = result.exceptionOrNull()
                continue
            }
            val groups = parseTopology(xml)
            _groups.value = groups
            _status.value = if (groups.isEmpty()) "Found Sonos at $host, but it reported no rooms." else null
            return
        }
        Log.w(TAG, "No Sonos answered topology", lastError)
        _status.value = "Found Sonos at ${hosts.first()}, but couldn't read its rooms" +
            (lastError?.message?.let { ": $it" } ?: ".")
    }

    private fun parseTopology(xml: String): List<SonosGroup> {
        val groups = ArrayList<SonosGroup>()
        val groupRegex = Regex("<ZoneGroup\\s([^>]*)>(.*?)</ZoneGroup>", RegexOption.DOT_MATCHES_ALL)
        val memberRegex = Regex("<ZoneGroupMember\\s([^>]*?)/?>")
        for (g in groupRegex.findAll(xml)) {
            val coordinator = attr(g.groupValues[1], "Coordinator") ?: continue
            val members = memberRegex.findAll(g.groupValues[2]).mapNotNull { m ->
                val a = m.groupValues[1]
                if (attr(a, "Invisible") == "1") return@mapNotNull null
                val uuid = attr(a, "UUID") ?: return@mapNotNull null
                val host = attr(a, "Location")?.let { runCatching { URL(it).host }.getOrNull() } ?: return@mapNotNull null
                synchronized(knownHosts) { knownHosts += host }
                SonosPlayer(uuid, attr(a, "ZoneName") ?: "Sonos", host, modelFor(host), coordinator)
            }.toList()
            val coord = members.firstOrNull { it.uuid == coordinator } ?: continue
            groups += SonosGroup(coord, members)
        }
        return groups.sortedBy { it.name }
    }

    private fun modelFor(host: String): String = models.getOrPut(host) {
        runCatching {
            val desc = URL("http://$host:1400/xml/device_description.xml").readText()
            Regex("<modelName>(.*?)</modelName>").find(desc)?.groupValues?.get(1)?.removePrefix("Sonos ") ?: "Sonos"
        }.getOrDefault("Sonos")
    }

    private fun attr(s: String, name: String) =
        Regex("\\b$name=\"([^\"]*)\"").find(s)?.groupValues?.get(1)?.let(SonosClient::unescape)

    companion object {
        private const val TAG = "SonosDiscovery"
    }
}
