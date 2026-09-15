package com.prabotics.rapidrop.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.prabotics.rapidrop.ui.HapticManager


class RapiDropTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        if (!SyncService.isConnected.value) {
            HapticManager.performError(this)
            val peer = SyncService.connectedPeerName.value ?: "device"
            Toast.makeText(this, "Not connected to $peer", Toast.LENGTH_SHORT).show()
            updateTileState()
            return
        }

        val intent = Intent(this, SendClipActionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isConnected = SyncService.isConnected.value

        val peer = SyncService.connectedPeerName.value ?: "Device"
        tile.state = if (isConnected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isConnected) "Sync to $peer" else "Sync Clipboard"
        tile.subtitle = if (isConnected) "Tap to send" else "Not connected"
        tile.updateTile()
    }
}
