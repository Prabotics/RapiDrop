package com.prabotics.rapidrop.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager



object HapticManager {
    private var isHapticEnabledCache: Boolean? = null

    fun updateHapticEnabled(enabled: Boolean) {
        isHapticEnabledCache = enabled
    }

    private fun isHapticEnabled(context: Context): Boolean {
        isHapticEnabledCache?.let { return it }
        val enabled = com.prabotics.rapidrop.preference.PreferencesManager(context).isHapticEnabled()
        isHapticEnabledCache = enabled
        return enabled
    }

    fun performClick(context: Context) {
        if (!isHapticEnabled(context)) return
        try {
            val vibrator = getVibrator(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(20)
            }
        } catch (_: Exception) {}
    }

    fun performSuccess(context: Context) {
        if (!isHapticEnabled(context)) return
        try {
            val vibrator = getVibrator(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 35, 45, 55)
                val amplitudes = intArrayOf(0, 180, 0, 255)
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 35, 45, 55), -1)
            }
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            audio?.playSoundEffect(android.media.AudioManager.FX_KEYPRESS_STANDARD, 1.0f)
        } catch (_: Exception) {}
    }

    fun performError(context: Context) {
        if (!isHapticEnabled(context)) return
        try {
            val vibrator = getVibrator(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 60, 40, 60, 40, 80)
                val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 60, 40, 60, 40, 80), -1)
            }
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            audio?.playSoundEffect(android.media.AudioManager.FX_KEYPRESS_DELETE, 1.0f)
        } catch (_: Exception) {}
    }
    private fun getVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }
}
