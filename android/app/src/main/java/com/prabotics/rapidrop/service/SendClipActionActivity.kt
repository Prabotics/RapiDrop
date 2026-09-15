package com.prabotics.rapidrop.service

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import com.prabotics.rapidrop.clipboard.ClipboardManagerHelper
import com.prabotics.rapidrop.ui.HapticManager



class SendClipActionActivity : Activity() {
    private var processed = false
    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        super.onCreate(savedInstanceState)

        if (!SyncService.isConnected.value) {
            HapticManager.performError(this)
            val peer = SyncService.connectedPeerName.value ?: "device"
            Toast.makeText(this, "Not connected to $peer", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val view = View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(view)
        view.requestFocus()

        handler.postDelayed({
            if (!processed) {
                attemptReadAndSend()
            }
        }, 1500)

        if (hasWindowFocus()) {
            attemptReadAndSend()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !processed) {
            attemptReadAndSend()
        }
    }

    private fun attemptReadAndSend() {
        if (processed) return

        val clip = ClipboardManagerHelper.readPrimaryClip(this)
        if (clip != null && (!clip.textContent.isNullOrBlank() || clip.rawData != null)) {
            processed = true
            SyncService.sendClip(this, clip)
            HapticManager.performSuccess(this)
            val peer = SyncService.connectedPeerName.value ?: "device"
            Toast.makeText(this, "Sent to $peer", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (retryCount < 5) {
            retryCount++
            handler.postDelayed({ attemptReadAndSend() }, 100)
            return
        }

        processed = true
        HapticManager.performError(this)
        Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun finish() {
        super.finish()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
