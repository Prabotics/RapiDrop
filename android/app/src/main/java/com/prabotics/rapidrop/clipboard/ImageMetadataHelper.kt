package com.prabotics.rapidrop.clipboard

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.LruCache
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale



data class ImageMetadata(
    val width: Int,
    val height: Int,
    val format: String,
    val formattedSize: String
) {
    val resolutionLabel: String
        get() = if (width > 0 && height > 0) "$width × $height" else ""

    val technicalSummary: String
        get() = buildString {
            if (format.isNotEmpty()) append(format)
            if (resolutionLabel.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append(resolutionLabel)
            }
            if (formattedSize.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append(formattedSize)
            }
        }
}

object ImageMetadataHelper {
    private val thumbnailCache = object : LruCache<UUID, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: UUID, value: Bitmap): Int {
            return value.byteCount
        }
    }

    fun getCachedThumbnail(id: UUID?): Bitmap? {
        if (id == null) return null
        return thumbnailCache.get(id)
    }

    fun extractMetadata(raw: ByteArray?): ImageMetadata? {
        if (raw == null || raw.isEmpty()) return null

        val format = detectFormat(raw)
        var width = 0
        var height = 0

        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
            width = options.outWidth
            height = options.outHeight
        } catch (_: Throwable) {
        }

        if (width <= 0 || height <= 0) {
            val fallbackDim = extractDimensionsFromHeader(raw, format)
            if (fallbackDim != null) {
                width = fallbackDim.first
                height = fallbackDim.second
            }
        }

        val formattedSize = formatFileSize(raw.size)

        return ImageMetadata(
            width = width,
            height = height,
            format = format,
            formattedSize = formattedSize
        )
    }
    fun decodeSampledBitmap(raw: ByteArray?, reqWidth: Int, reqHeight: Int): Bitmap? {
        return decodeSampledBitmap(null, raw, reqWidth, reqHeight)
    }

    fun decodeSampledBitmap(id: UUID?, raw: ByteArray?, reqWidth: Int, reqHeight: Int): Bitmap? {
        if (id != null) {
            val cached = thumbnailCache.get(id)
            if (cached != null) return cached
        }
        if (raw == null || raw.isEmpty()) return null
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
            if (bitmap != null && id != null) {
                thumbnailCache.put(id, bitmap)
            }
            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun detectFormat(bytes: ByteArray): String {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return "PNG"
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return "JPG"
        }
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            return "WEBP"
        }
        if (bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte()
        ) {
            return "GIF"
        }
        return "IMG"
    }

    fun formatFileSize(bytes: Int): String {
        return if (bytes < 1024 * 1024) {
            "${bytes / 1024} KB"
        } else {
            String.format(Locale.US, "%.1f MB", bytes.toFloat() / (1024f * 1024f))
        }
    }

    private fun extractDimensionsFromHeader(bytes: ByteArray, format: String): Pair<Int, Int>? {
        return try {
            when (format) {
                "PNG" -> {
                    if (bytes.size >= 24) {
                        val buffer = ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN)
                        val w = buffer.int
                        val h = buffer.int
                        Pair(w, h)
                    } else null
                }
                "GIF" -> {
                    if (bytes.size >= 10) {
                        val buffer = ByteBuffer.wrap(bytes, 6, 4).order(ByteOrder.LITTLE_ENDIAN)
                        val w = buffer.short.toInt() and 0xFFFF
                        val h = buffer.short.toInt() and 0xFFFF
                        Pair(w, h)
                    } else null
                }
                "JPG" -> {
                    var i = 2
                    while (i < bytes.size - 8) {
                        if (bytes[i] == 0xFF.toByte()) {
                            val marker = bytes[i + 1].toInt() and 0xFF
                            if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                                val h = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                                val w = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                                return Pair(w, h)
                            } else {
                                val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                                i += 2 + length
                            }
                        } else {
                            i++
                        }
                    }
                    null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
