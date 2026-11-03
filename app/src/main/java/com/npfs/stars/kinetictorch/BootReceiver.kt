package com.npfs.stars.kinetictorch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = context.getSharedPreferences(ChopDetectionService.PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, false)) {
                val serviceIntent = Intent(context, ChopDetectionService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
