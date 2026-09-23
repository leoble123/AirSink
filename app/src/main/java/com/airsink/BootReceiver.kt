package com.airsink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the headphones service after a reboot so the pop-up and ear detection keep working. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) HeadphonesService.start(context)
    }
}
