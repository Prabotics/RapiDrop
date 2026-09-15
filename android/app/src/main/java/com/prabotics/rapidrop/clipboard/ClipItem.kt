package com.prabotics.rapidrop.clipboard

import java.util.UUID



enum class ClipContentType {
    TEXT,
    URL,
    IMAGE,
    FILE
}

data class ClipItem(
    val id: UUID = UUID.randomUUID(),
    val type: ClipContentType,
    val textContent: String? = null,
    val fileName: String? = null,
    val rawData: ByteArray? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val displayName: String
        get() = when (type) {
            ClipContentType.TEXT, ClipContentType.URL -> textContent ?: ""
            ClipContentType.IMAGE -> {
                if (!fileName.isNullOrBlank()) {
                    fileName
                } else {
                    val ext = rawData?.let { bytes ->
                        when (ImageMetadataHelper.detectFormat(bytes)) {
                            "PNG" -> "png"
                            "JPEG" -> "jpg"
                            "WEBP" -> "webp"
                            "GIF" -> "gif"
                            else -> "png"
                        }
                    } ?: "png"
                    val dateStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(timestamp))
                    "Clip_$dateStr.$ext"
                }
            }
            ClipContentType.FILE -> fileName ?: "File_${id.toString().take(6)}.bin"
        }

    val previewText: String
        get() = when (type) {
            ClipContentType.TEXT, ClipContentType.URL -> textContent ?: ""
            ClipContentType.IMAGE -> displayName
            ClipContentType.FILE -> "$displayName (${(rawData?.size ?: 0) / 1024} KB)"
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClipItem
        if (id != other.id) return false
        if (type != other.type) return false
        if (textContent != other.textContent) return false
        if (fileName != other.fileName) return false
        if (rawData != null) {
            if (other.rawData == null) return false
            if (!rawData.contentEquals(other.rawData)) return false
        } else if (other.rawData != null) return false
        if (timestamp != other.timestamp) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + (textContent?.hashCode() ?: 0)
        result = 31 * result + (fileName?.hashCode() ?: 0)
        result = 31 * result + (rawData?.contentHashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
