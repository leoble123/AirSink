package com.airsink

import android.app.Application
import com.airsink.airplay.AirPlayDiscovery
import com.airsink.airpods.AirPodsManager
import com.airsink.cast.CastManager
import com.airsink.core.Permissions
import com.airsink.core.Prefs
import com.airsink.sonos.SonosDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-wide singletons. Small enough that a DI framework would be overkill. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val prefs = Prefs(app)
    val airpods = AirPodsManager(app, scope, prefs)
    val airplay = AirPlayDiscovery(app)
    val sonos = SonosDiscovery(app, scope)
    val cast = CastManager(app, scope, prefs)
}

class AirSinkApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        if (Permissions.hasBluetooth(this)) {
            graph.airpods.start()
            HeadphonesService.start(this)
        }
    }
}
