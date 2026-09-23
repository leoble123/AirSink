package com.airsink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.airsink.airpods.Battery
import com.airsink.airpods.HeadphonesState
import com.airsink.core.Permissions
import com.airsink.ui.popup.PopupOverlay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps headphone features alive while the app is closed: BLE scanning for the case-open
 * popup, automatic ear detection and the battery notification.
 */
class HeadphonesService : LifecycleService() {
    private val graph get() = (application as AirSinkApp).graph
    private var popup: PopupOverlay? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        popup = PopupOverlay(this, this, graph)

        lifecycleScope.launch {
            graph.airpods.state
                .map { it?.let { s -> Triple(s.name, listOf(s.left, s.right, s.case, s.headset), s.connected) } }
                .combine(graph.melody.state.map { m -> m?.let { listOf(it.name, it.left, it.right, it.case) } }) { a, b -> a to b }
                .distinctUntilChanged()
                .collect {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(graph.airpods.state.value))
                }
        }
        lifecycleScope.launch {
            graph.airpods.popups.collect { msg ->
                if (graph.prefs.connectionPopup.value && Settings.canDrawOverlays(this@HeadphonesService)) {
                    popup?.show(msg)
                }
            }
        }
    }

    override fun onDestroy() {
        popup?.dismiss()
        super.onDestroy()
    }

    private fun notification(state: HeadphonesState?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Headphones", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Battery levels and background features for your AirPods"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val melody = graph.melody.state.value
        val (title, text) = when {
            state == null && melody != null -> melody.name to listOfNotNull(
                melody.left?.let { "L ${it.level}%" }, melody.right?.let { "R ${it.level}%" }, melody.case?.let { "Case ${it.level}%" },
            ).joinToString("  ·  ").ifEmpty { "Connected" }
            state == null -> "AirSink" to "Looking for your AirPods"
            state.model.hasCase -> state.name to listOfNotNull(
                fmt("L", state.left), fmt("R", state.right), fmt("Case", state.case),
            ).joinToString("  ·  ").ifEmpty { if (state.connected) "Connected" else "Nearby" }
            else -> state.name to (fmt("Battery", state.headset) ?: "Connected")
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun fmt(label: String, b: Battery) =
        b.level?.let { "$label ${it}%${if (b.charging) " ⚡" else ""}" }

    companion object {
        private const val CHANNEL = "headphones"
        private const val NOTIFICATION_ID = 7

        fun start(context: Context) {
            if (!Permissions.hasBluetooth(context)) return
            // Android 12+ refuses foreground-service starts from the background in some states;
            // the service simply starts next time the app is opened.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, HeadphonesService::class.java)) }
        }
    }
}
