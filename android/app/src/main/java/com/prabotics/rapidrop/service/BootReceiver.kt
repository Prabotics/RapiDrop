package com.prabotics.rapidrop.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.prabotics.rapidrop.preference.PreferencesManager



class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = PreferencesManager(context)
            if (prefs.getPairingPin() != null) {
                SyncService.start(context)
            }
        }
    }
}
