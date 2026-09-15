package com.prabotics.rapidrop.network

import android.content.Context
import android.os.Build
import android.provider.Settings



object DeviceNameHelper {
    fun getDeviceFriendlyName(context: Context): String {
        val userDeviceName = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
                ?: Settings.System.getString(context.contentResolver, "bluetooth_name")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

        if (!userDeviceName.isNullOrBlank() && !userDeviceName.equals("Android", ignoreCase = true)) {
            return userDeviceName.trim()
        }

        val model = Build.MODEL?.trim().orEmpty()
        val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }.orEmpty()
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else if (manufacturer.isNotBlank()) {
            "$manufacturer $model".trim()
        } else {
            model.ifBlank { "Android Device" }
        }
    }

    fun normalizeDeviceName(name: String?): String {
        if (name.isNullOrBlank()) return ""
        var n = name.replace('’', '\'')
            .replace('‘', '\'')
            .replace("\"", "")
            .replace("\\", "")
            .trim()
        n = n.replace(Regex("""\s*\(\d+\)$"""), "")
        n = n.replace(Regex("""\s*-\s*\d+$"""), "")
        return n.trim().lowercase()
    }

    fun isSameDevice(name1: String?, name2: String?): Boolean {
        if (name1.isNullOrBlank() || name2.isNullOrBlank()) return false
        val n1 = normalizeDeviceName(name1)
        val n2 = normalizeDeviceName(name2)
        return n1 == n2
    }
}
