package com.npfs.stars.kinetictorch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DebugLog.start(context)
        val action = intent.action
        DebugLog.event("boot_receiver", "reason" to action)
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = context.getSharedPreferences(ChopDetectionService.PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, false)) {
                val serviceIntent = Intent(context, ChopDetectionService::class.java)
                DebugLog.action("boot_start_service") { context.startForegroundService(serviceIntent) }
            }
        }
    }
}

