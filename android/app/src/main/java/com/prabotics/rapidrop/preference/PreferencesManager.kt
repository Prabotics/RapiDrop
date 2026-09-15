package com.prabotics.rapidrop.preference

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.prabotics.rapidrop.clipboard.MediaDestinationMode
import com.prabotics.rapidrop.network.WireFrame
import com.prabotics.rapidrop.security.CryptoEngine
import com.prabotics.rapidrop.ui.HapticManager
import com.prabotics.rapidrop.ui.theme.AppThemeMode
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec



class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "rapidrop_secure_prefs"
        private const val KEY_PEER_NAME = "peer_name"
        private const val KEY_PEER_HOST = "peer_host"
        private const val KEY_PEER_PORT = "peer_port"
        private const val KEY_PEER_ID = "peer_id"
        private const val KEY_PAIRING_PIN = "pairing_pin"
        private const val KEY_PAIRED_KEY = "paired_session_key"
        private const val KEY_MEDIA_DESTINATION_MODE = "media_destination_mode"
        private const val KEY_APP_THEME_MODE = "app_theme_mode"
        private const val KEY_PINNED_CLIP_IDS = "pinned_clip_ids"
        private const val KEY_SHOW_SYNC_HISTORY = "show_sync_history"
        private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
        private const val KEY_DEVICE_ID = "device_id"
    }

    fun savePairingPin(pin: String) {
        prefs.edit().putString(KEY_PAIRING_PIN, KeystoreHelper.encrypt(pin)).apply()
    }

    fun getPairingPin(): String? {
        val stored = prefs.getString(KEY_PAIRING_PIN, null) ?: return null
        return KeystoreHelper.decrypt(stored)
    }

    fun clearPairingPin() {
        prefs.edit().remove(KEY_PAIRING_PIN).apply()
    }

    fun savePairedKey(keyHex: String) {
        prefs.edit().putString(KEY_PAIRED_KEY, KeystoreHelper.encrypt(keyHex)).apply()
    }

    fun getPairedKey(): String? {
        val stored = prefs.getString(KEY_PAIRED_KEY, null) ?: return null
        return KeystoreHelper.decrypt(stored)
    }

    fun clearPairedKey() {
        prefs.edit().remove(KEY_PAIRED_KEY).apply()
    }

    fun getPeerId(): String? = prefs.getString(KEY_PEER_ID, null)
    fun getDeviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }
    fun clearPeerInfo() {
        prefs.edit()
            .remove(KEY_PEER_NAME)
            .remove(KEY_PEER_HOST)
            .remove(KEY_PEER_PORT)
            .remove(KEY_PEER_ID)
            .apply()
    }

    fun savePeerInfo(name: String, host: String, port: Int, id: String? = null) {
        val editor = prefs.edit()
            .putString(KEY_PEER_NAME, name)
            .putString(KEY_PEER_HOST, host)
            .putInt(KEY_PEER_PORT, port)
        if (!id.isNullOrBlank()) {
            editor.putString(KEY_PEER_ID, id)
        }
        editor.apply()
    }

    fun getPeerName(): String? = prefs.getString(KEY_PEER_NAME, null)
    fun getPeerHost(): String? = prefs.getString(KEY_PEER_HOST, null)
    fun getPeerPort(): Int = prefs.getInt(KEY_PEER_PORT, WireFrame.DEFAULT_PORT)

    fun getMediaDestinationMode(): MediaDestinationMode {
        val modeName = prefs.getString(KEY_MEDIA_DESTINATION_MODE, null) ?: return MediaDestinationMode.BOTH
        return try {
            MediaDestinationMode.valueOf(modeName)
        } catch (_: Exception) {
            MediaDestinationMode.BOTH
        }
    }

    fun setMediaDestinationMode(mode: MediaDestinationMode) {
        prefs.edit().putString(KEY_MEDIA_DESTINATION_MODE, mode.name).apply()
    }

    fun getAppThemeMode(): AppThemeMode {
        val name = prefs.getString(KEY_APP_THEME_MODE, null) ?: return AppThemeMode.SYSTEM
        return try {
            AppThemeMode.valueOf(name)
        } catch (_: Exception) {
            AppThemeMode.SYSTEM
        }
    }

    fun setAppThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_APP_THEME_MODE, mode.name).apply()
    }

    fun getPinnedClipIds(): Set<String> {
        return prefs.getStringSet(KEY_PINNED_CLIP_IDS, emptySet()) ?: emptySet()
    }

    fun togglePinClip(id: String): Set<String> {
        val current = getPinnedClipIds().toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        prefs.edit().putStringSet(KEY_PINNED_CLIP_IDS, current).apply()
        return current
    }
    fun removePinnedClip(id: String): Set<String> {
        val current = getPinnedClipIds().toMutableSet()
        if (current.remove(id)) {
            prefs.edit().putStringSet(KEY_PINNED_CLIP_IDS, current).apply()
        }
        return current
    }

    fun isShowSyncHistory(): Boolean {
        return prefs.getBoolean(KEY_SHOW_SYNC_HISTORY, true)
    }

    fun setShowSyncHistory(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SYNC_HISTORY, enabled).apply()
    }

    fun isHapticEnabled(): Boolean {
        val enabled = prefs.getBoolean(KEY_HAPTIC_ENABLED, true)
        HapticManager.updateHapticEnabled(enabled)
        return enabled
    }

    fun setHapticEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()
        HapticManager.updateHapticEnabled(enabled)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

private object KeystoreHelper {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "rapidrop_credential_key"
    private const val PREFIX = "enc:"

    private fun getOrCreateKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            } else {
                (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun encrypt(plainText: String): String {
        val key = getOrCreateKey() ?: return plainText
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv ?: return plainText
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
            PREFIX + CryptoEngine.bytesToHex(combined)
        } catch (_: Throwable) {
            plainText
        }
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        val key = getOrCreateKey() ?: return stored.removePrefix(PREFIX)
        return try {
            val hex = stored.removePrefix(PREFIX)
            val combined = CryptoEngine.hexToBytes(hex) ?: return stored
            if (combined.size < 12) return stored
            val iv = ByteArray(12)
            val cipherBytes = ByteArray(combined.size - 12)
            System.arraycopy(combined, 0, iv, 0, 12)
            System.arraycopy(combined, 12, cipherBytes, 0, cipherBytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, Charsets.UTF_8)
        } catch (_: Throwable) {
            stored.removePrefix(PREFIX)
        }
    }
}
