package com.airsink

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: shows whether audio is streaming; tap to stop or open the speaker picker. */
class CastTileService : TileService() {
    private val cast get() = (application as AirSinkApp).graph.cast

    override fun onStartListening() {
        val tile = qsTile ?: return
        val active = cast.active.value
        tile.state = if (active.isNotEmpty()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "AirSink"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (active.size) {
                0 -> "Off"
                1 -> active.values.first().displayName
                else -> "${active.size} speakers"
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        if (cast.active.value.isNotEmpty()) {
            cast.stopAll()
            onStartListening()
            return
        }
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
