package com.prabotics.rapidrop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.Manifest
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import android.content.res.Configuration
import androidx.activity.result.contract.ActivityResultContracts
import com.prabotics.rapidrop.ui.theme.RapiDropTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.prabotics.rapidrop.clipboard.ClipContentType
import androidx.compose.runtime.LaunchedEffect
import com.prabotics.rapidrop.clipboard.ClipItem
import com.prabotics.rapidrop.clipboard.ClipboardManagerHelper
import com.prabotics.rapidrop.network.PairInviteInfo
import com.prabotics.rapidrop.service.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.prabotics.rapidrop.preference.PreferencesManager
import com.prabotics.rapidrop.ui.theme.AppThemeMode
import java.io.File



class MainActivity : ComponentActivity() {
    private lateinit var preferencesManager: PreferencesManager
    private var lastSentClipHash: Int = 0
    private val primaryClipListener = ClipboardManager.OnPrimaryClipChangedListener {
        syncForegroundClipboard()
    }
    private var isNotificationGrantedState = mutableStateOf(true)
    private var isBatteryOptimizedIgnoredState = mutableStateOf(true)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissionStates()
    }

    private val pickMultipleMediaLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            handleSendMultipleFiles(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        var isReady = false
        splashScreen.setKeepOnScreenCondition { !isReady }
        preferencesManager = PreferencesManager(this)
        val initialMode = preferencesManager.getAppThemeMode()
        val isDark = when (initialMode) {
            AppThemeMode.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            AppThemeMode.LIGHT -> false
            AppThemeMode.DARK -> true
        }
        enableEdgeToEdge(
            statusBarStyle = if (isDark) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT
                )
            },
            navigationBarStyle = if (isDark) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT
                )
            }
        )
        super.onCreate(savedInstanceState)
        refreshPermissionStates()
        val initDevice = intent?.getStringExtra("EXTRA_PAIR_DEVICE")
        val initPin = intent?.getStringExtra("EXTRA_PAIR_PIN")
        if (initDevice != null) {
            if (!SyncService.isConnected.value && SyncService.pairingPin.value == null) {
                SyncService.incomingPairInvite.value = PairInviteInfo(initDevice, initPin)
            }
            intent?.removeExtra("EXTRA_PAIR_DEVICE")
            intent?.removeExtra("EXTRA_PAIR_PIN")
        }

        handleIntentActions(intent)
        SyncService.start(this)
        setContent {
            LaunchedEffect(Unit) {
                isReady = true
            }
            var appThemeMode by remember { mutableStateOf(preferencesManager.getAppThemeMode()) }
            RapiDropTheme(themeMode = appThemeMode) {
                val isConnected by SyncService.isConnected.collectAsState()
                val isConnecting by SyncService.isConnecting.collectAsState()
                val deviceInfo by SyncService.connectedDeviceInfo.collectAsState()
                val pairingPin by SyncService.pairingPin.collectAsState()
                val connectedPeerName by SyncService.connectedPeerName.collectAsState()
                val context = LocalContext.current
                var wasConnected by remember { mutableStateOf(isConnected) }
                LaunchedEffect(isConnected, connectedPeerName) {
                    if (isConnected && !wasConnected && !connectedPeerName.isNullOrBlank()) {
                        Toast.makeText(context, "Connected to $connectedPeerName", Toast.LENGTH_SHORT).show()
                    }
                    wasConnected = isConnected
                }
                val discoveredDevices by SyncService.discoveredDevices.collectAsState()
                val recentClips by SyncService.recentClips.collectAsState()
                val pinnedClipIds by SyncService.pinnedClipIds.collectAsState()
                val mediaDestinationMode by SyncService.mediaDestinationMode.collectAsState()
                val showSyncHistory by SyncService.showSyncHistory.collectAsState()
                val incomingPairInvite by SyncService.incomingPairInvite.collectAsState()
                val outgoingPairInvite by SyncService.outgoingPairInvite.collectAsState()
                val pairingError by SyncService.pairingError.collectAsState()
                var syncState by remember { mutableStateOf(SyncState.IDLE) }
                val activeTransferProgress by SyncService.activeTransferProgress.collectAsState()
                val isNotificationGranted by isNotificationGrantedState
                val isBatteryOptimizedIgnored by isBatteryOptimizedIgnoredState
                MainScreen(
                    state = MainScreenState(
                        activeTransferProgress = activeTransferProgress,
                        isConnected = isConnected,
                        isConnecting = isConnecting,
                        deviceInfo = deviceInfo,
                        discoveredDevices = discoveredDevices,
                        pinnedClipIds = pinnedClipIds,
                        showSyncHistory = showSyncHistory,
                        recentClips = recentClips,
                        pairingPin = pairingPin,
                        connectedPeerName = connectedPeerName,
                        syncState = syncState,
                        mediaDestinationMode = mediaDestinationMode,
                        appThemeMode = appThemeMode,
                        isNotificationGranted = isNotificationGranted,
                        isBatteryOptimizedIgnored = isBatteryOptimizedIgnored,
                        incomingPairInvite = incomingPairInvite,
                        outgoingPairInvite = outgoingPairInvite,
                        pairingError = pairingError
                    ),
                    callbacks = MainScreenCallbacks(
                        onCancelTransfer = {
                            SyncService.cancelActiveTransfer(this@MainActivity)
                        },
                        onCancelOutgoingInvite = {
                            SyncService.cancelOutgoingPairInvite()
                        },
                        onDeclineIncomingInvite = { invite ->
                            SyncService.declineIncomingPairInvite(invite)
                        },
                        onPairDevice = { device, pin ->
                            SyncService.pairWithDevice(this@MainActivity, device, pin)
                        },
                        onSendPairInvite = { device, pin ->
                            SyncService.sendPairInvite(this@MainActivity, device, pin)
                        },
                        onUnpair = {
                            SyncService.unpair(this@MainActivity)
                        },
                        onSendMediaClick = {
                            pickMultipleMediaLauncher.launch(arrayOf("*/*"))
                        },
                        onSendClipboardClick = {
                            handleSyncNowClick(
                                onSyncing = { syncState = SyncState.SYNCING },
                                onSuccess = {
                                    syncState = SyncState.SUCCESS
                                    lifecycleScope.launch {
                                        delay(1600)
                                        syncState = SyncState.IDLE
                                    }
                                },
                                onError = { syncState = SyncState.IDLE }
                            )
                        },
                        onCopyClip = { clip ->
                            handleCopyClip(clip)
                        },
                        onShareClip = { clip ->
                            handleShareClip(clip)
                        },
                        onClearHistory = {
                            handleClearHistory()
                        },
                        onDeleteClip = { clip ->
                            HapticManager.performClick(this@MainActivity)
                            SyncService.deleteClip(clip.id.toString(), this@MainActivity)
                        },
                        onSendClip = { clip ->
                            handleSendClip(clip)
                        },
                        onTogglePinClip = { clip ->
                            HapticManager.performClick(this@MainActivity)
                            SyncService.togglePinClip(this@MainActivity, clip.id.toString())
                        },
                        onToggleShowSyncHistory = {
                            SyncService.setShowSyncHistory(this@MainActivity, !showSyncHistory)
                        },
                        onMediaDestinationModeChange = { mode ->
                            SyncService.setMediaDestinationMode(this@MainActivity, mode)
                        },
                        onThemeModeChange = { mode ->
                            appThemeMode = mode
                            preferencesManager.setAppThemeMode(mode)
                        },
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onRequestBatteryExemption = {
                            requestBatteryOptimizationExemption()
                        }
                    )
                )
            }
        }
    }

    private fun handleIntentActions(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            "com.prabotics.rapidrop.ACTION_SEND_FILES" -> {
                pickMultipleMediaLauncher.launch(arrayOf("*/*"))
            }
            "com.prabotics.rapidrop.ACTION_SYNC_CLIPBOARD" -> {
                syncForegroundClipboard()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentActions(intent)
        val device = intent.getStringExtra("EXTRA_PAIR_DEVICE")
        val pin = intent.getStringExtra("EXTRA_PAIR_PIN")
        if (device != null) {
            if (!SyncService.isConnected.value && SyncService.pairingPin.value == null) {
                SyncService.incomingPairInvite.value = PairInviteInfo(device, pin)
            }
            intent.removeExtra("EXTRA_PAIR_DEVICE")
            intent.removeExtra("EXTRA_PAIR_PIN")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
        SyncService.start(this)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.addPrimaryClipChangedListener(primaryClipListener)
        syncForegroundClipboard()
    }

    override fun onPause() {
        super.onPause()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.removePrimaryClipChangedListener(primaryClipListener)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            syncForegroundClipboard()
        }
    }

    private fun syncForegroundClipboard() {
        if (!SyncService.isConnected.value) return
        val clip = ClipboardManagerHelper.readPrimaryClip(this) ?: return
        if (clip.textContent.isNullOrBlank() && clip.rawData == null) return
        val currentHash = clip.textContent?.hashCode() ?: (clip.rawData?.let { java.util.Arrays.hashCode(it) } ?: 0)
        if (currentHash == SyncService.lastReceivedClipHash || currentHash == lastSentClipHash) {
            return
        }
        lastSentClipHash = currentHash
        SyncService.sendClip(this, clip)
    }

    private fun refreshPermissionStates() {
        isNotificationGrantedState.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isBatteryOptimizedIgnoredState.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(packageName) ?: true
        } else true
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (_: android.content.ActivityNotFoundException) {}
                } catch (_: SecurityException) {}
            }
        }
    }

    private fun handleCopyClip(item: ClipItem) {
        ClipboardManagerHelper.writeToClipboard(this, item)
        HapticManager.performSuccess(this)
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun handleSendClip(item: ClipItem) {
        if (!SyncService.isConnected.value) {
            HapticManager.performError(this)
            val peer = SyncService.connectedPeerName.value ?: "device"
            Toast.makeText(this, "Not connected to $peer", Toast.LENGTH_SHORT).show()
            return
        }
        SyncService.sendClip(this, item)
        HapticManager.performSuccess(this)
        val peer = SyncService.connectedPeerName.value ?: "device"
        Toast.makeText(this, "Sent to $peer", Toast.LENGTH_SHORT).show()
    }

    private fun handleShareClip(item: ClipItem) {
        try {
            when (item.type) {
                ClipContentType.TEXT, ClipContentType.URL -> {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, item.textContent ?: "")
                    }
                    startActivity(Intent.createChooser(sendIntent, "Share clip"))
                }
                ClipContentType.IMAGE, ClipContentType.FILE -> {
                    val bytes = item.rawData
                    if (bytes != null && bytes.isNotEmpty()) {
                        val ext = if (item.type == ClipContentType.IMAGE) "png" else "bin"
                        val tempFile = File(cacheDir, item.fileName ?: "shared_clip_${System.currentTimeMillis()}.$ext")
                        tempFile.writeBytes(bytes)
                        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tempFile)
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = if (item.type == ClipContentType.IMAGE) "image/*" else "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(sendIntent, "Share ${if (item.type == ClipContentType.IMAGE) "image" else "file"}"))
                    }
                }
            }
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "No app available to handle share", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Permission denied to share", Toast.LENGTH_SHORT).show()
        } catch (_: java.io.IOException) {
            Toast.makeText(this, "Failed to prepare file for sharing", Toast.LENGTH_SHORT).show()
        } catch (_: IllegalArgumentException) {
            Toast.makeText(this, "Invalid file for sharing", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleClearHistory() {
        SyncService.clearHistory()
        HapticManager.performClick(this)
        Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
    }

    private fun handleSendMultipleFiles(uris: List<Uri>) {
        if (!SyncService.isConnected.value) {
            val peer = SyncService.connectedPeerName.value ?: "device"
            Toast.makeText(this, "Not connected to $peer", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val fileList = mutableListOf<Triple<String, Long, java.io.InputStream>>()
            for (uri in uris) {
                var fileName: String? = null
                var fileSize: Long = 0L
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) fileName = cursor.getString(nameIndex)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) fileSize = cursor.getLong(sizeIndex)
                    }
                }
                val stream = contentResolver.openInputStream(uri)
                if (stream != null) {
                    fileList.add(Triple(fileName ?: "file_${System.currentTimeMillis()}", maxOf(0L, fileSize), stream))
                }
            }
            if (fileList.isNotEmpty()) {
                SyncService.sendStreamingFiles(this@MainActivity, fileList)
                withContext(Dispatchers.Main) {
                    HapticManager.performSuccess(this@MainActivity)
                }
            }
        }
    }

    private fun handleSyncNowClick(
        onSyncing: () -> Unit,
        onSuccess: () -> Unit,
        onError: () -> Unit
    ) {
        if (!SyncService.isConnected.value) {
            HapticManager.performError(this)
            val peer = SyncService.connectedPeerName.value ?: "device"
            Toast.makeText(this, "Not connected to $peer", Toast.LENGTH_SHORT).show()
            onError()
            return
        }

        val clip = ClipboardManagerHelper.readPrimaryClip(this)
        if (clip == null || (clip.textContent.isNullOrBlank() && clip.rawData == null)) {
            HapticManager.performError(this)
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            onError()
            return
        }

        onSyncing()
        SyncService.sendClip(this, clip)
        HapticManager.performSuccess(this)
        val peer = SyncService.connectedPeerName.value ?: "device"
        Toast.makeText(this, "Sent to $peer", Toast.LENGTH_SHORT).show()
        onSuccess()
    }
}
