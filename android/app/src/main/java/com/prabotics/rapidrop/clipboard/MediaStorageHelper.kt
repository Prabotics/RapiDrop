package com.prabotics.rapidrop.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream



enum class MediaDestinationMode {
    BOTH,
    FOLDER_ONLY,
    CLIPBOARD_ONLY;

    val displayLabel: String
        get() = when (this) {
            BOTH -> "Folder & Clipboard"
            FOLDER_ONLY -> "Folder Only"
            CLIPBOARD_ONLY -> "Clipboard Only"
        }

    val shortLabel: String
        get() = when (this) {
            BOTH -> "Both"
            FOLDER_ONLY -> "Folder"
            CLIPBOARD_ONLY -> "Clipboard"
        }
    val description: String
        get() = when (this) {
            BOTH -> "Save files to dedicated folders and copy to clipboard"
            FOLDER_ONLY -> "Save files to dedicated folders without touching clipboard"
            CLIPBOARD_ONLY -> "Copy media to clipboard only without permanent storage"
        }
}

object MediaStorageHelper {
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun saveMediaToPublicFolder(context: Context, item: ClipItem): Uri? {
        val data = item.rawData ?: return null
        val resolver = context.contentResolver

        return try {
            when (item.type) {
                ClipContentType.IMAGE, ClipContentType.FILE -> {
                    val isImage = item.type == ClipContentType.IMAGE
                    val sanitizedName = item.fileName?.let { File(it).name.trim() }?.takeIf { it.isNotBlank() }
                    val rawName = sanitizedName ?: if (isImage) "RapiDrop_${System.currentTimeMillis()}.png" else "RapiDrop_${System.currentTimeMillis()}.bin"
                    val fileName = if (rawName.contains('.')) rawName else if (isImage) "$rawName.png" else rawName
                    val mimeType = when {
                        fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
                        fileName.endsWith(".png", true) -> "image/png"
                        fileName.endsWith(".webp", true) -> "image/webp"
                        fileName.endsWith(".gif", true) -> "image/gif"
                        fileName.endsWith(".pdf", true) -> "application/pdf"
                        fileName.endsWith(".mp4", true) -> "video/mp4"
                        fileName.endsWith(".webm", true) -> "video/webm"
                        else -> if (isImage) "image/png" else "application/octet-stream"
                    }

                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            "${Environment.DIRECTORY_DOWNLOADS}/RapiDrop"
                        )
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }

                    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val itemUri = resolver.insert(collection, values) ?: return null

                    resolver.openOutputStream(itemUri)?.use { out ->
                        out.write(data)
                        out.flush()
                    }

                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)

                    val publicPath = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "RapiDrop/$fileName"
                    ).absolutePath
                    MediaScannerConnection.scanFile(context, arrayOf(publicPath), arrayOf(mimeType), null)

                    itemUri
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    fun sanitizeRelativePath(relativePath: String, fallbackFileName: String = "file"): String {
        val cleanFallback = File(fallbackFileName.replace('\\', '/')).name.trim()
        val fallback = if (cleanFallback.isBlank()) "file" else cleanFallback
        val normalized = relativePath.replace('\\', '/')
        val safeComponents = normalized.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." && !it.contains(':') && !it.contains('\u0000') }
        return if (safeComponents.isEmpty()) fallback else safeComponents.joinToString("/")
    }

    fun openOutputStreamForDownload(context: Context, fileName: String, relativePath: String): Pair<java.io.OutputStream?, Uri?> {
        return try {
            val resolver = context.contentResolver
            val safeRelPath = sanitizeRelativePath(relativePath, fileName)
            val safeName = File(fileName.replace('\\', '/')).name.trim().ifBlank { "file" }
            val folderPath = if (safeRelPath.contains('/')) {
                "${Environment.DIRECTORY_DOWNLOADS}/RapiDrop/" + safeRelPath.substringBeforeLast('/')
            } else {
                "${Environment.DIRECTORY_DOWNLOADS}/RapiDrop"
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.RELATIVE_PATH, folderPath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collection, values) ?: return Pair(null, null)
            val stream = resolver.openOutputStream(itemUri)
            Pair(stream, itemUri)
        } catch (_: java.io.IOException) {
            Pair(null, null)
        } catch (_: SecurityException) {
            Pair(null, null)
        } catch (_: IllegalStateException) {
            Pair(null, null)
        }
    }

    fun finalizeDownloadUri(context: Context, uri: Uri) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {}
    }

    fun deleteDownloadUri(context: Context, uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {}
    }

    fun writeMediaToClipboard(context: Context, item: ClipItem): Boolean {
        val data = item.rawData ?: return false
        cleanupOldCacheFiles(context)

        return try {
            val extension = when (item.type) {
                ClipContentType.IMAGE -> {
                    val name = item.fileName?.let { File(it).name } ?: ""
                    if (name.contains('.')) name.substringAfterLast('.') else "png"
                }
                ClipContentType.FILE -> {
                    val name = item.fileName?.let { File(it).name } ?: ""
                    if (name.contains('.')) name.substringAfterLast('.') else "bin"
                }
                else -> return false
            }

            val cacheFile = File(context.cacheDir, "clip_${System.currentTimeMillis()}.$extension")
            FileOutputStream(cacheFile).use { out ->
                out.write(data)
                out.flush()
            }

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, cacheFile)

            mainHandler.post {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@post
                val clipData = ClipData.newUri(context.contentResolver, "RapiDrop", uri)
                clipboard.setPrimaryClip(clipData)
            }
            true
        } catch (_: java.io.IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun cleanupOldCacheFiles(context: Context) {
        try {
            val files = context.cacheDir.listFiles { file ->
                file.name.startsWith("clip_")
            } ?: return

            if (files.size > 20) {
                val sorted = files.sortedBy { it.lastModified() }
                val toDelete = sorted.take(files.size - 20)
                toDelete.forEach { it.delete() }
            }
        } catch (_: SecurityException) {}
    }
}
