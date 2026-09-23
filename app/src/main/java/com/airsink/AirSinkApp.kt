package com.airsink

import android.app.Application
import com.airsink.airplay.AirPlayDiscovery
import com.airsink.airpods.AirPodsManager
import com.airsink.cast.CastManager
import com.airsink.core.Permissions
import com.airsink.core.Prefs
import com.airsink.melody.MelodyManager
import com.airsink.sonos.SonosDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** App-wide singletons. Small enough that a DI framework would be overkill. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val prefs = Prefs(app)
    val airpods = AirPodsManager(app, scope, prefs)
    val melody = MelodyManager(app, scope)
    val airplay = AirPlayDiscovery(app)
    val sonos = SonosDiscovery(app, scope)
    val cast = CastManager(app, scope, prefs)

    init {
        // Sonos speakers that advertise AirPlay also answer Sonos's own control API on the
        // same address, so AirPlay discovery doubles as a second way to find them.
        scope.launch {
            airplay.devices.collect { devices ->
                val hosts = devices.values.filter { it.kind == com.airsink.airplay.AirPlayKind.SONOS }.map { it.host }
                if (hosts.isNotEmpty()) sonos.addHosts(hosts)
            }
        }
    }
}

class AirSinkApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        if (Permissions.hasBluetooth(this)) {
            graph.airpods.start()
            graph.melody.start()
            HeadphonesService.start(this)
        }
    }
}
