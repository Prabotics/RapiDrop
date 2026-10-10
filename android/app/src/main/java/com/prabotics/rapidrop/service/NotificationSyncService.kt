package com.prabotics.rapidrop.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import android.util.LruCache
import com.prabotics.rapidrop.preference.PreferencesManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern
import kotlinx.coroutines.flow.MutableStateFlow

data class MirroredNotice(
    val id: String,
    val appName: String,
    val title: String,
    val text: String,
    val otpCode: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

class NotificationSyncService : NotificationListenerService() {

    companion object {
        var instance: NotificationSyncService? = null
            private set

        private val OTP_PATTERN = Pattern.compile("\\b(?:\\d{4,8}|[A-Z0-9]{6})\\b")
        private val OTP_CONTEXT = Pattern.compile("(?i)(code|otp|verification|verify|pin|login|auth|one-time)")
        private val iconCache = LruCache<String, String>(64)
        val recentNotices = MutableStateFlow<List<MirroredNotice>>(emptyList())

        fun clearNotices() {
            recentNotices.value = emptyList()
        }

        fun extractAppIconBase64(context: Context, packageName: String): String? {
            iconCache.get(packageName)?.let { return it }
            return try {
                val pm = context.packageManager
                val drawable = pm.getApplicationIcon(packageName)
                val width = 64
                val height = 64
                val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                    Bitmap.createScaledBitmap(drawable.bitmap, width, height, true)
                } else {
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, width, height)
                    drawable.draw(canvas)
                    bmp
                }
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                val encoded = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                iconCache.put(packageName, encoded)
                encoded
            } catch (_: Exception) {
                null
            }
        }

        fun isPermissionGranted(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val myComponent = "${context.packageName}/${NotificationSyncService::class.java.name}"
            return enabledListeners.split(":").any { it.equals(myComponent, ignoreCase = true) || it.contains(context.packageName) }
        }

        fun openSettings(context: Context) {
            val componentName = "${context.packageName}/${NotificationSyncService::class.java.name}"
            val directIntent = Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS").apply {
                putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", componentName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(directIntent)
            } catch (_: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(fallbackIntent)
                } catch (_: Exception) {}
            }
        }

        fun dismiss(id: String) {
            instance?.cancelNotification(id)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) {
            instance = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val prefs = PreferencesManager(this)
        if (!prefs.isNotificationMirroringEnabled()) return
        if (!SyncService.isConnected.value) return

        val notification = sbn.notification ?: return
        val packageName = sbn.packageName ?: return

        if (packageName == applicationContext.packageName) return

        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 || sbn.isOngoing
        if (isOngoing) return

        if (notification.visibility == Notification.VISIBILITY_SECRET) return

        val extras = notification.extras ?: return
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val bigTitle = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.trim()
        val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()

        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.filterNotNull()
            ?.map { it.toString().trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString("\n")

        val messagesBundle = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val extractedMessages = messagesBundle?.mapNotNull { item ->
            if (item is android.os.Bundle) {
                val sender = item.getCharSequence("sender")?.toString()?.trim()
                val msgText = item.getCharSequence("text")?.toString()?.trim()
                if (!msgText.isNullOrBlank()) {
                    if (!sender.isNullOrBlank()) "$sender: $msgText" else msgText
                } else null
            } else null
        }?.joinToString("\n")

        val rawText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: (if (!extractedMessages.isNullOrBlank()) extractedMessages else null)
            ?: (if (!textLines.isNullOrBlank()) textLines else null)
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.trim()
            ?: notification.tickerText?.toString()?.trim()
        val pm = packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }

        val resolvedTitle = when {
            !convTitle.isNullOrBlank() -> convTitle
            !bigTitle.isNullOrBlank() -> bigTitle
            !rawTitle.isNullOrBlank() -> rawTitle
            !rawText.isNullOrBlank() -> appName
            else -> return
        }
        val resolvedText = rawText ?: ""

        val fullBody = listOfNotNull(resolvedTitle, resolvedText).joinToString(" ")
        var detectedOtp: String? = null
        if (OTP_CONTEXT.matcher(fullBody).find()) {
            val matcher = OTP_PATTERN.matcher(fullBody)
            if (matcher.find()) {
                detectedOtp = matcher.group()
            }
        }
        val appIconBase64 = extractAppIconBase64(this, packageName)

        val id = sbn.key ?: "${sbn.id}_${sbn.packageName}"
        val json = JSONObject().apply {
            put("id", id)
            put("packageName", packageName)
            put("appName", appName)
            put("title", resolvedTitle)
            put("text", resolvedText)
            put("timestamp", sbn.postTime)
            put("canDismiss", sbn.isClearable)
            if (!detectedOtp.isNullOrBlank()) {
                put("otpCode", detectedOtp)
            }
            if (!appIconBase64.isNullOrBlank()) {
                put("iconBase64", appIconBase64)
            }
        }
        Log.d("RapiDrop", "notification.mirroring: pkg=$packageName")
        SyncService.instance?.sendNotificationEvent(json.toString())
        val notice = MirroredNotice(
            id = id,
            appName = appName,
            title = resolvedTitle,
            text = resolvedText,
            otpCode = detectedOtp,
            timestamp = sbn.postTime
        )
        recentNotices.value = (listOf(notice) + recentNotices.value.filter { it.id != id }).take(30)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
