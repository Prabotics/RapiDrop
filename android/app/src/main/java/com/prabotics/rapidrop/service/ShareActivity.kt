package com.prabotics.rapidrop.service

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import com.prabotics.rapidrop.clipboard.ClipContentType
import com.prabotics.rapidrop.clipboard.ClipItem
import com.prabotics.rapidrop.ui.HapticManager



class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            finish()
            return
        }

        if (!SyncService.isConnected.value) {
            HapticManager.performError(this)
            val peer = SyncService.connectedPeerName.value ?: "device"
            Toast.makeText(this, "Not connected to $peer", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (action == Intent.ACTION_SEND_MULTIPLE) {
            val streamUris: java.util.ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (!streamUris.isNullOrEmpty()) {
                handleStreamUris(streamUris)
            } else {
                finish()
            }
        } else {
            val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

            if (streamUri != null) {
                handleStreamUris(listOf(streamUri))
            } else {
                handleTextShare()
            }
        }

        finish()
    }

    private fun handleStreamUris(uris: List<Uri>) {
        val fileList = mutableListOf<Triple<String, Long, java.io.InputStream>>()
        for (uri in uris) {
            var fileName: String? = null
            var fileSize = 0L
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                            fileName = cursor.getString(nameIndex)
                        }
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            fileSize = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: RuntimeException) {}

            if (fileName.isNullOrBlank()) {
                fileName = uri.lastPathSegment ?: "file_${System.currentTimeMillis()}"
            }

            try {
                val stream = contentResolver.openInputStream(uri)
                if (stream != null) {
                    fileList.add(Triple(fileName, maxOf(0L, fileSize), stream))
                }
            } catch (_: java.io.IOException) {
            } catch (_: SecurityException) {}
        }

        if (fileList.isNotEmpty()) {
            SyncService.sendStreamingFiles(this, fileList)
            HapticManager.performSuccess(this)
            val peer = SyncService.connectedPeerName.value ?: "device"
            val label = if (fileList.size == 1) fileList.first().first else "${fileList.size} files"
            Toast.makeText(this, "Sending $label to $peer", Toast.LENGTH_SHORT).show()
        } else {
            HapticManager.performError(this)
            Toast.makeText(this, "Failed to read shared files", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleTextShare() {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            val isUrl = text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)
            val item = ClipItem(
                type = if (isUrl) ClipContentType.URL else ClipContentType.TEXT,
                textContent = text
            )
            SyncService.sendClip(this, item)
            HapticManager.performSuccess(this)
            val peer = SyncService.connectedPeerName.value ?: "device"
            Toast.makeText(this, "Sent to $peer", Toast.LENGTH_SHORT).show()
        }
    }
}
