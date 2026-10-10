package com.prabotics.rapidrop.network

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class DeviceBatteryStatus(
    val level: Int,
    val isCharging: Boolean,
) {
    companion object {
        fun current(context: Context?): DeviceBatteryStatus? {
            if (context == null) return null
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

            val rawLevel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                ?: if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 else -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val percent = if (scale > 0 && rawLevel >= 0) {
                ((rawLevel * 100) / scale).coerceIn(0, 100)
            } else if (rawLevel in 0..100) {
                rawLevel
            } else {
                return null
            }

            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val isPlugged = plugged > 0
            val isStatusCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val isBmCharging = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && bm != null) {
                bm.isCharging
            } else {
                false
            }
            val isCharging = isPlugged || isStatusCharging || isBmCharging
            return DeviceBatteryStatus(percent, isCharging)
        }
    }
}
