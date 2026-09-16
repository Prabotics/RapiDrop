package com.prabotics.rapidrop.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper



object ClipboardManagerHelper {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun writeToClipboard(context: Context, item: ClipItem) {
        mainHandler.post {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@post
            val clipData: ClipData = when (item.type) {
                ClipContentType.TEXT, ClipContentType.URL -> {
                    ClipData.newPlainText("RapiDrop", item.textContent ?: "")
                }
                ClipContentType.IMAGE, ClipContentType.FILE -> {
                    MediaStorageHelper.writeMediaToClipboard(context, item)
                    return@post
                }
            }
            clipboard.setPrimaryClip(clipData)
        }
    }

    fun readPrimaryClip(context: Context): ClipItem? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val primaryClip = clipboard.primaryClip ?: return null
        if (primaryClip.itemCount == 0) return null

        val description = primaryClip.description
        if (description != null) {
            val extras = description.extras
            if (extras != null && (
                extras.getBoolean("android.content.extra.IS_SENSITIVE", false) ||
                extras.getInt("android.content.extra.IS_SENSITIVE", 0) == 1
            )) {
                return null
            }
        }

        for (i in 0 until primaryClip.itemCount) {
            val item = primaryClip.getItemAt(i) ?: continue
            val rawText = item.text?.toString()
                ?: item.htmlText
                ?: try { item.coerceToText(context)?.toString() } catch (_: SecurityException) { null } catch (_: RuntimeException) { null }

            if (!rawText.isNullOrBlank()) {
                val isUrl = rawText.startsWith("http://", ignoreCase = true) || rawText.startsWith("https://", ignoreCase = true)
                return ClipItem(
                    type = if (isUrl) ClipContentType.URL else ClipContentType.TEXT,
                    textContent = rawText
                )
            }

            val uri = item.uri
            if (uri != null) {
                val uriString = uri.toString()
                if (uriString.startsWith("http://", ignoreCase = true) || uriString.startsWith("https://", ignoreCase = true)) {
                    return ClipItem(type = ClipContentType.URL, textContent = uriString)
                }
                val mimeType = context.contentResolver.getType(uri)
                if (mimeType?.startsWith("image/") == true) {
                    try {
                        val descriptor = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null }
                        val size = descriptor?.statSize ?: 0L
                        descriptor?.close()
                        if (size in 1..com.prabotics.rapidrop.network.WireFrame.MAX_PAYLOAD_SIZE) {
                            val stream = context.contentResolver.openInputStream(uri)
                            val bytes = stream?.use { it.readBytes() }
                            if (bytes != null && bytes.isNotEmpty()) {
                                return ClipItem(type = ClipContentType.IMAGE, rawData = bytes)
                            }
                        }
                    } catch (_: java.io.IOException) {
                    } catch (_: SecurityException) {} catch (_: OutOfMemoryError) {}
                }
            }
        }
        return null
    }
}
