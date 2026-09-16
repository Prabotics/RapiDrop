package com.prabotics.rapidrop.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.prabotics.rapidrop.clipboard.ClipContentType
import com.prabotics.rapidrop.clipboard.ClipItem
import com.prabotics.rapidrop.clipboard.ClipboardManagerHelper
import com.prabotics.rapidrop.clipboard.ConnectedDeviceInfo
import com.prabotics.rapidrop.clipboard.MediaDestinationMode
import com.prabotics.rapidrop.clipboard.MediaStorageHelper
import com.prabotics.rapidrop.network.ClientSocketServer
import com.prabotics.rapidrop.network.DeviceNameHelper
import com.prabotics.rapidrop.network.DiscoveredDevice
import com.prabotics.rapidrop.network.NsdDiscovery
import com.prabotics.rapidrop.network.SocketClient
import com.prabotics.rapidrop.R
import com.prabotics.rapidrop.security.CryptoEngine
import com.prabotics.rapidrop.security.HandshakeKeys
import com.prabotics.rapidrop.preference.PreferencesManager
import com.prabotics.rapidrop.ui.MainActivity
import com.prabotics.rapidrop.ui.HapticManager
import com.prabotics.rapidrop.network.PairInviteInfo
import com.prabotics.rapidrop.network.WireFrame
import com.prabotics.rapidrop.network.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch



data class TransferProgress(
    val transferId: String,
    val fileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val fileIndex: Int,
    val totalFiles: Int,
    val isSending: Boolean = false,
    val speedBytesPerSec: Double = 0.0
) {
    val progressFraction: Float
        get() = if (totalBytes > 0) (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val remainingSeconds: Long?
        get() {
            if (speedBytesPerSec < 10_000 || totalBytes <= bytesTransferred) return null
            val remainingBytes = (totalBytes - bytesTransferred).toDouble()
            return (remainingBytes / speedBytesPerSec).toLong()
        }

    val formattedEta: String?
        get() {
            val sec = remainingSeconds ?: return null
            if (sec < 1) return null
            return if (sec < 60) {
                "${sec}s left"
            } else {
                val mins = sec / 60
                val remSec = sec % 60
                "${mins}m ${remSec}s left"
            }
        }
}
class TransferSpeedTracker {
    private var lastProgressUpdateTime = 0L
    private var lastBytesCheckpoint = 0L
    private var lastCheckpointTime = System.currentTimeMillis()
    private var smoothedSpeed = 0.0

    fun reset(initialBytes: Long = 0L) {
        val now = System.currentTimeMillis()
        lastProgressUpdateTime = now
        lastBytesCheckpoint = initialBytes
        lastCheckpointTime = now
        smoothedSpeed = 0.0
    }

    fun update(bytes: Long, totalBytes: Long): Pair<Boolean, Double> {
        val now = System.currentTimeMillis()
        val isFinished = bytes >= totalBytes
        if (!isFinished && now - lastProgressUpdateTime < 250L) {
            return Pair(false, smoothedSpeed)
        }
        val timeDelta = now - lastCheckpointTime
        if (timeDelta >= 200L) {
            val bytesDelta = bytes - lastBytesCheckpoint
            val instantSpeed = (bytesDelta.toDouble() / timeDelta) * 1000.0
            smoothedSpeed = if (smoothedSpeed == 0.0) instantSpeed else (smoothedSpeed * 0.7 + instantSpeed * 0.3)
            lastBytesCheckpoint = bytes
            lastCheckpointTime = now
        }
        lastProgressUpdateTime = now
        return Pair(true, smoothedSpeed)
    }
}

class SyncService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var socketClient: SocketClient
    private lateinit var nsdDiscovery: NsdDiscovery

    private var autoReconnectJob: Job? = null
    private var pairingTimeoutJob: Job? = null
    private var activeOutgoingTransferJob: Job? = null

    companion object {
        const val FOREGROUND_CHANNEL_ID = "rapidrop_status_channel"
        const val PAIRING_CHANNEL_ID = "rapidrop_pairing_channel"
        const val FOREGROUND_NOTIFICATION_ID = 1001
        const val PAIRING_NOTIFICATION_ID = 1002
        const val ACTION_STOP_SERVICE = "com.prabotics.rapidrop.ACTION_STOP_SERVICE"
        val isConnected = MutableStateFlow(false)
        val isConnecting = MutableStateFlow(false)
        val connectedPeerName = MutableStateFlow<String?>(null)
        val connectedDeviceInfo = MutableStateFlow<ConnectedDeviceInfo?>(null)
        val pairingPin = MutableStateFlow<String?>(null)
        val discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
        val lastSyncedText = MutableStateFlow<String?>(null)
        val recentClips = MutableStateFlow<List<ClipItem>>(emptyList())
        val pinnedClipIds = MutableStateFlow<Set<String>>(emptySet())
        val showSyncHistory = MutableStateFlow(true)
        val mediaDestinationMode = MutableStateFlow(MediaDestinationMode.BOTH)
        val incomingPairInvite = MutableStateFlow<PairInviteInfo?>(null)
        val outgoingPairInvite = MutableStateFlow<PairInviteInfo?>(null)
        val pairingError = MutableStateFlow<String?>(null)
        val activeTransferProgress = MutableStateFlow<TransferProgress?>(null)
        var lastReceivedClipHash: Int = 0
        @androidx.annotation.VisibleForTesting
        var instance: SyncService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: IllegalStateException) {
            } catch (_: SecurityException) {}
        }

        fun pairWithDevice(context: Context, device: DiscoveredDevice, pin: String) {
            val s = instance
            if (s != null) {
                s.pairWithDevice(device, pin)
            } else {
                incomingPairInvite.value = null
                outgoingPairInvite.value = null
                context.getSystemService(NotificationManager::class.java)?.cancel(PAIRING_NOTIFICATION_ID)
                start(context)
                val prefs = PreferencesManager(context)
                val clean = pin.filter { it.isDigit() }.take(6)
                if (clean.length == 6) {
                    pairingPin.value = clean
                    connectedPeerName.value = device.name
                    prefs.savePairingPin(clean)
                    prefs.savePeerInfo(device.name, device.host, device.port)
                }
            }
        }

        fun sendPairInvite(context: Context, device: DiscoveredDevice, pin: String) {
            val s = instance
            if (s != null) {
                s.sendPairInvite(device, pin)
            } else {
                start(context)
                val clean = pin.filter { it.isDigit() }.take(6)
                outgoingPairInvite.value = PairInviteInfo(device.name, clean, device.host, device.port)
            }
        }

        fun cancelOutgoingPairInvite() {
            instance?.cancelOutgoingPairInvite() ?: run {
                outgoingPairInvite.value = null
                pairingError.value = null
            }
        }

        fun declineIncomingPairInvite(invite: PairInviteInfo) {
            incomingPairInvite.value = null
            val s = instance
            val notificationManager = s?.getSystemService(NotificationManager::class.java)
            notificationManager?.cancel(PAIRING_NOTIFICATION_ID)
            s?.declineIncomingPairInvite(invite)
        }

        fun unpair(context: Context) {
            val s = instance
            if (s != null) {
                s.unpair()
            } else {
                val prefs = PreferencesManager(context)
                prefs.clearPairingPin()
                prefs.clearPeerInfo()
                pairingPin.value = null
                isConnected.value = false
                connectedDeviceInfo.value = null
                connectedPeerName.value = null
            }
        }
        fun sendClip(context: Context, item: ClipItem) {
            val s = instance
            if (s != null) {
                s.sendClip(item)
            } else {
                start(context)
            }
        }
        fun cancelActiveTransfer(context: Context) {
            val s = instance ?: return
            val progress = activeTransferProgress.value ?: return
            val tid = progress.transferId
            val isSending = progress.isSending
            activeTransferProgress.value = null
            s.releaseWakeLock()
            if (isSending) {
                s.activeOutgoingTransferJob?.cancel()
                s.activeOutgoingTransferJob = null
            } else {
                s.socketClient.cancelIncomingTransfer()
            }
            s.serviceScope.launch(Dispatchers.IO) {
                s.socketClient.sendCancelTransfer(tid, "user_cancelled")
            }
        }

        fun sendStreamingFiles(context: Context, files: List<Triple<String, Long, java.io.InputStream>>) {
            val s = instance ?: return
            s.acquireWakeLock()
            s.acquireWifiLock()
            s.activeOutgoingTransferJob?.cancel()
            s.activeOutgoingTransferJob = s.serviceScope.launch(Dispatchers.IO) {
                try {
                    val totalBytes = files.sumOf { it.second }
                    val transferId = java.util.UUID.randomUUID().toString()
                    var overallBytesSent = 0L
                    val speedTracker = TransferSpeedTracker()
                    speedTracker.reset(0L)

                    for ((index, item) in files.withIndex()) {
                        val (name, size, stream) = item
                        val fileOffset = overallBytesSent
                        try {
                            s.socketClient.sendStreamingFile(
                                fileName = name,
                                relativePath = name,
                                fileSize = size,
                                fileIndex = index,
                                totalFiles = files.size,
                                totalBytes = totalBytes,
                                transferId = transferId,
                                inputStream = stream
                            ) { bytesInFile ->
                                val currentOverall = fileOffset + bytesInFile
                                val (shouldUpdate, speed) = speedTracker.update(currentOverall, totalBytes)
                                if (shouldUpdate) {
                                    activeTransferProgress.value = TransferProgress(
                                        transferId = transferId,
                                        fileName = name,
                                        bytesTransferred = currentOverall,
                                        totalBytes = totalBytes,
                                        fileIndex = index + 1,
                                        totalFiles = files.size,
                                        isSending = true,
                                        speedBytesPerSec = speed
                                    )
                                }
                            }
                            overallBytesSent += size
                        } catch (_: Exception) {
                            activeTransferProgress.value = null
                            return@launch
                        }
                    }
                    val clip = ClipItem(type = ClipContentType.FILE, fileName = files.firstOrNull()?.first ?: "File", rawData = null)
                    addRecentClip(clip)
                } finally {
                    for (item in files) {
                        try { item.third.close() } catch (_: Exception) {}
                    }
                    activeTransferProgress.value = null
                    s.releaseWakeLock()
                    s.releaseWifiLock()
                }
            }
        }

        fun addRecentClip(item: ClipItem) {
            lastSyncedText.value = item.previewText
            if (!showSyncHistory.value) {
                return
            }
            val current = recentClips.value.toMutableList()
            current.removeAll { existing ->
                if (existing.type != item.type) return@removeAll false
                when (item.type) {
                    ClipContentType.TEXT, ClipContentType.URL -> {
                        existing.textContent == item.textContent
                    }
                    ClipContentType.IMAGE -> {
                        (existing.rawData != null && item.rawData != null && existing.rawData.contentEquals(item.rawData)) ||
                            (!existing.fileName.isNullOrBlank() && existing.fileName == item.fileName)
                    }
                    ClipContentType.FILE -> {
                        (!existing.fileName.isNullOrBlank() && existing.fileName == item.fileName) ||
                            (existing.rawData != null && item.rawData != null && existing.rawData.contentEquals(item.rawData))
                    }
                }
            }
            current.add(0, item)
            val pinned = pinnedClipIds.value
            if (current.size > 20) {
                val pinnedItems = current.filter { it.id.toString() in pinned }
                val unpinnedItems = current.filter { it.id.toString() !in pinned }
                recentClips.value = (pinnedItems + unpinnedItems).take(20)
            } else {
                recentClips.value = current
            }
        }

        fun setShowSyncHistory(context: Context, enabled: Boolean) {
            showSyncHistory.value = enabled
            instance?.preferencesManager?.setShowSyncHistory(enabled)
                ?: PreferencesManager(context).setShowSyncHistory(enabled)
            if (!enabled) {
                val pinned = pinnedClipIds.value
                recentClips.value = recentClips.value.filter { it.id.toString() in pinned }
            }
        }

        fun togglePinClip(context: Context, id: String) {
            val updated = instance?.preferencesManager?.togglePinClip(id)
                ?: PreferencesManager(context).togglePinClip(id)
            pinnedClipIds.value = updated
        }

        fun clearHistory() {
            val pinned = pinnedClipIds.value
            recentClips.value = recentClips.value.filter { it.id.toString() in pinned }
            lastSyncedText.value = null
        }
        fun deleteClip(id: String, context: Context? = null) {
            recentClips.value = recentClips.value.filter { it.id.toString() != id }
            if (id in pinnedClipIds.value) {
                val updated = instance?.preferencesManager?.removePinnedClip(id)
                    ?: context?.let { PreferencesManager(it).removePinnedClip(id) }
                    ?: (pinnedClipIds.value - id)
                pinnedClipIds.value = updated
            }
        }

        fun setMediaDestinationMode(context: Context, mode: MediaDestinationMode) {
            mediaDestinationMode.value = mode
            instance?.preferencesManager?.setMediaDestinationMode(mode)
                ?: PreferencesManager(context).setMediaDestinationMode(mode)
        }
    }
    private var socketServer: ClientSocketServer? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var pendingIncomingKeys: com.prabotics.rapidrop.security.HandshakeKeys? = null
    private var pendingOutgoingKeys: com.prabotics.rapidrop.security.HandshakeKeys? = null
    private var pendingAcceptPayload: String? = null
    inner class LocalBinder : Binder() {
        fun getService(): SyncService = this@SyncService
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onCreate() {
        super.onCreate()
        instance = this
        preferencesManager = PreferencesManager(this)

        val savedPin = preferencesManager.getPairingPin()
        pairingPin.value = savedPin
        mediaDestinationMode.value = preferencesManager.getMediaDestinationMode()
        pinnedClipIds.value = preferencesManager.getPinnedClipIds()
        showSyncHistory.value = preferencesManager.isShowSyncHistory()
        val savedPeer = preferencesManager.getPeerName()
        if (savedPeer != null) {
            connectedPeerName.value = savedPeer
        }

        socketClient = SocketClient(
            context = this,
            onClipReceived = { clipItem ->
                val hash = clipItem.textContent?.hashCode() ?: (clipItem.rawData?.let { java.util.Arrays.hashCode(it) } ?: 0)
                lastReceivedClipHash = hash
                val mode = mediaDestinationMode.value
                when (clipItem.type) {
                    ClipContentType.IMAGE, ClipContentType.FILE -> {
                        if (mode == MediaDestinationMode.BOTH || mode == MediaDestinationMode.FOLDER_ONLY) {
                            MediaStorageHelper.saveMediaToPublicFolder(this, clipItem)
                        }
                        if (mode == MediaDestinationMode.BOTH || mode == MediaDestinationMode.CLIPBOARD_ONLY) {
                            MediaStorageHelper.writeMediaToClipboard(this, clipItem)
                        }
                    }
                    ClipContentType.TEXT, ClipContentType.URL -> {
                        ClipboardManagerHelper.writeToClipboard(this, clipItem)
                    }
                }
                addRecentClip(clipItem)
                HapticManager.performSuccess(this@SyncService)
            },
            onDeviceInfoReceived = { info ->
                connectedDeviceInfo.value = info
                connectedPeerName.value = info.deviceName
                val match = discoveredDevices.value.find { DeviceNameHelper.isSameDevice(it.name, info.deviceName) }
                val host = match?.host ?: preferencesManager.getPeerHost()
                val port = match?.port ?: preferencesManager.getPeerPort()
                if (!host.isNullOrBlank()) {
                    preferencesManager.savePeerInfo(info.deviceName, host, port)
                }
                updateNotification()
            },
            onConnectionStateChanged = { connected ->
                isConnecting.value = false
                if (isConnected.value != connected) {
                    isConnected.value = connected
                    if (connected) {
                        startAutoReconnect()
                        acquireWifiLock()
                        android.util.Log.d("RapiDrop", "connection.connected")
                        pairingError.value = null
                        HapticManager.performSuccess(this@SyncService)
                        val pin = pairingPin.value
                        val peer = connectedPeerName.value
                        if (pin != null) {
                            preferencesManager.savePairingPin(pin)
                            if (peer != null) {
                                val match = discoveredDevices.value.find { DeviceNameHelper.isSameDevice(it.name, peer) }
                                if (match != null) {
                                    preferencesManager.savePeerInfo(peer, match.host, match.port)
                                }
                            }
                        }
                        val initialClip = ClipboardManagerHelper.readPrimaryClip(this@SyncService)
                        if (initialClip != null && (!initialClip.textContent.isNullOrBlank() || initialClip.rawData != null)) {
                            val currentHash = initialClip.textContent?.hashCode() ?: (initialClip.rawData?.let { java.util.Arrays.hashCode(it) } ?: 0)
                            if (currentHash != lastReceivedClipHash) {
                                lastReceivedClipHash = currentHash
                                addRecentClip(initialClip)
                                serviceScope.launch {
                                    socketClient.sendClip(initialClip)
                                }
                            }
                        }
                    } else {
                        releaseWakeLock()
                        releaseWifiLock()
                        activeTransferProgress.value = null
                        connectedDeviceInfo.value = null
                        nsdDiscovery.restartDiscovery()
                    }
                    updateNotification()
                }
            },
            onPairFailed = { reason ->
                if (outgoingPairInvite.value != null) {
                    pairingError.value = "Pairing request declined or failed."
                    outgoingPairInvite.value = null
                    pairingPin.value = null
                    connectedPeerName.value = null
                    preferencesManager.clearPairingPin()
                    updateNotification()
                }
            },
            onRemoteUnpaired = {
                preferencesManager.clearPairingPin()
                pairingPin.value = null
                connectedPeerName.value = null
                isConnected.value = false
                connectedDeviceInfo.value = null
                autoReconnectJob?.cancel()
                updateNotification()
            }
        )
        socketClient.onPrivacyModeUpdated = { _ ->
        }
        var rxLastProgressTime = 0L
        var rxLastBytesCheckpoint = 0L
        var rxLastCheckpointTime = System.currentTimeMillis()
        var rxSmoothedSpeed = 0.0
        var currentRxTransferId: String? = null

        socketClient.onTransferProgress = { transferId, fileName, bytes, total, index, totalFiles, isComplete ->
            if (isComplete) {
                releaseWakeLock()
                releaseWifiLock()
                activeTransferProgress.value = null
                rxSmoothedSpeed = 0.0
                currentRxTransferId = null
            } else {
                acquireWakeLock()
                acquireWifiLock()
                val now = System.currentTimeMillis()
                if (currentRxTransferId != transferId) {
                    currentRxTransferId = transferId
                    rxLastProgressTime = now
                    rxLastBytesCheckpoint = bytes
                    rxLastCheckpointTime = now
                    rxSmoothedSpeed = 0.0
                }
                val timeDelta = now - rxLastCheckpointTime
                if (timeDelta >= 200L) {
                    val bytesDelta = bytes - rxLastBytesCheckpoint
                    val instantSpeed = (bytesDelta.toDouble() / timeDelta) * 1000.0
                    rxSmoothedSpeed = if (rxSmoothedSpeed == 0.0) instantSpeed else (rxSmoothedSpeed * 0.7 + instantSpeed * 0.3)
                    rxLastBytesCheckpoint = bytes
                    rxLastCheckpointTime = now
                }
                if (now - rxLastProgressTime >= 250L || bytes >= total) {
                    rxLastProgressTime = now
                    activeTransferProgress.value = TransferProgress(
                        transferId = transferId,
                        fileName = fileName,
                        bytesTransferred = bytes,
                        totalBytes = total,
                        fileIndex = index + 1,
                        totalFiles = totalFiles,
                        isSending = false,
                        speedBytesPerSec = rxSmoothedSpeed
                    )
                }
            }
        }
        socketClient.onTransferCancelled = { _, reason ->
            releaseWakeLock()
            releaseWifiLock()
            activeTransferProgress.value = null
        }

        val savedPairedKey = preferencesManager.getPairedKey()
        if (savedPairedKey != null) {
            val keyBytes = CryptoEngine.hexToBytes(savedPairedKey)
            if (keyBytes != null && keyBytes.size == 32) {
                socketClient.setSessionKey(javax.crypto.spec.SecretKeySpec(keyBytes, "AES"))
            }
        } else if (savedPin != null) {
            val initialKey = CryptoEngine.deriveKeyFromPin(savedPin)
            socketClient.setSessionKey(initialKey)
        }

        nsdDiscovery = NsdDiscovery(this) { devices ->
            discoveredDevices.value = devices
            if (!isConnected.value && pairingPin.value != null) {
                val savedPeerName = preferencesManager.getPeerName()
                val match = devices.find { DeviceNameHelper.isSameDevice(it.name, savedPeerName) }
                if (match != null) {
                    preferencesManager.savePeerInfo(savedPeerName ?: match.name, match.host, match.port)
                    serviceScope.launch {
                        socketClient.connect(match.host, match.port)
                    }
                }
            }
        }

        createNotificationChannels()
        startInForeground()

        nsdDiscovery.startDiscovery()
        nsdDiscovery.registerClientService()

        socketServer = ClientSocketServer(
            context = this,
            scope = serviceScope,
            onPairInviteReceived = { fromDevice, fromId, pin, host, port, keys, acceptPayload, offerPayload ->
                if (!isConnected.value || !DeviceNameHelper.isSameDevice(fromDevice, connectedPeerName.value)) {
                    val currentOutgoing = outgoingPairInvite.value
                    if (currentOutgoing != null && DeviceNameHelper.isSameDevice(fromDevice, currentOutgoing.deviceName)) {
                        val localId = preferencesManager.getDeviceId()
                        if (localId.isNotBlank() && fromId.isNotBlank() && localId < fromId) {
                            return@ClientSocketServer
                        } else {
                            pairingTimeoutJob?.cancel()
                            pairingTimeoutJob = null
                            pendingOutgoingKeys = null
                            outgoingPairInvite.value = null
                        }
                    }

                    val currentInvite = incomingPairInvite.value
                    val isDuplicate = currentInvite != null &&
                        DeviceNameHelper.isSameDevice(currentInvite.deviceName, fromDevice) &&
                        currentInvite.pin == pin
                    pendingIncomingKeys = keys
                    pendingAcceptPayload = acceptPayload
                    incomingPairInvite.value = PairInviteInfo(fromDevice, pin, host, port)
                    if (!isDuplicate) {
                        showPairInviteNotification(fromDevice, pin)
                    }
                    if (host != null && host.isNotBlank() && port > 0) {
                        serviceScope.launch {
                            socketClient.sendPairAccept(host, port, offerPayload)
                        }
                    }
                }
            },
            onPairAcceptReceived = { fromDevice, fromId, host, port, remotePubHex, remoteNonceHex, status ->
                val pending = outgoingPairInvite.value
                if (pending != null && DeviceNameHelper.isSameDevice(pending.deviceName, fromDevice)) {
                    val isAccepted = (status == "accepted")
                    val keys = socketClient.handlePairAccept(fromDevice, fromId, remotePubHex, remoteNonceHex, isFinal = isAccepted)
                        ?: pendingOutgoingKeys
                    if (keys != null) {
                        pendingOutgoingKeys = keys
                        android.util.Log.d("RapiDrop", "authentication.keys_derived status=$status")
                        outgoingPairInvite.value = pending.copy(pin = keys.sasCode)
                        if (isAccepted) {
                            pendingOutgoingKeys = null
                            val targetHost = host?.takeIf { it.isNotBlank() } ?: pending.host ?: ""
                            val targetPort = if (port > 0 && port != WireFrame.DEFAULT_CLIENT_PORT) port else WireFrame.DEFAULT_PORT
                            val target = DiscoveredDevice(name = fromDevice, host = targetHost, port = targetPort, id = fromId)
                            val sessionKey = socketClient.getSessionKey()
                            if (sessionKey != null) {
                                preferencesManager.savePairedKey(CryptoEngine.bytesToHex(sessionKey.encoded))
                            }
                            pairWithDevice(target, keys.sasCode)
                        }
                    }
                }
            },
            onPairDeclinedReceived = { reason ->
                outgoingPairInvite.value = null
                if (reason == "CANCELLED") {
                    incomingPairInvite.value = null
                }
                pairingError.value = null
                HapticManager.performError(this@SyncService)
            },
            onRemoteUnpairedReceived = {
                unpair()
            }
        )
        socketServer?.start()

        startAutoReconnect()
        registerNetworkCallback()
    }
    private fun startAutoReconnect() {
        autoReconnectJob?.cancel()
        autoReconnectJob = serviceScope.launch {
            var backoffMs = 3000L
            while (isActive) {
                if (pairingPin.value == null) {
                    break
                }
                if (isConnected.value) {
                    backoffMs = 3000L
                    delay(5000)
                } else {
                    val savedPeerName = preferencesManager.getPeerName()
                    val discovered = discoveredDevices.value.find { DeviceNameHelper.isSameDevice(it.name, savedPeerName) }
                    val rawHost = discovered?.host ?: preferencesManager.getPeerHost()
                    val targetHost = rawHost?.removePrefix("::ffff:")?.substringBefore("%")
                    val targetPort = discovered?.port ?: preferencesManager.getPeerPort()
                    if (discovered != null && targetHost != null) {
                        preferencesManager.savePeerInfo(savedPeerName ?: discovered.name, targetHost, targetPort)
                    }
                    if (!targetHost.isNullOrBlank()) {
                        socketClient.connect(targetHost, targetPort)
                    }
                    if (!isConnected.value) {
                        val jitter = (0..500).random()
                        delay(backoffMs + jitter)
                        backoffMs = (backoffMs * 1.5).toLong().coerceAtMost(20000L)
                    } else {
                        backoffMs = 3000L
                    }
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    nsdDiscovery.restartDiscovery()
                    startAutoReconnect()
                }

                override fun onLost(network: android.net.Network) {
                    socketClient.disconnect()
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: SecurityException) {
        } catch (_: RuntimeException) {}
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            }
            networkCallback = null
        } catch (_: RuntimeException) {}
    }
    private fun acquireWifiLock() {
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "RapiDrop_ActiveSync")?.apply {
                    setReferenceCounted(false)
                }
            }
            wifiLock?.acquire()
        } catch (_: SecurityException) {
        } catch (_: RuntimeException) {}
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: RuntimeException) {}
    }
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "RapiDrop:TransferWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (_: SecurityException) {
        } catch (_: RuntimeException) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: RuntimeException) {}
    }

    fun pairWithDevice(device: DiscoveredDevice, pin: String) {
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        val wasIncomingInvite = incomingPairInvite.value != null || pendingIncomingKeys != null
        val inviteInfo = incomingPairInvite.value
        incomingPairInvite.value = null
        outgoingPairInvite.value = null
        getSystemService(NotificationManager::class.java)?.cancel(PAIRING_NOTIFICATION_ID)

        pairingError.value = null
        pairingPin.value = pin
        connectedPeerName.value = device.name
        isConnecting.value = true

        preferencesManager.savePairingPin(pin)
        val targetHost = device.host.ifBlank { inviteInfo?.host ?: "" }
        val targetPort = if (device.port > 0) device.port else (inviteInfo?.port ?: WireFrame.DEFAULT_PORT)
        if (targetHost.isNotBlank() && targetPort > 0) {
            preferencesManager.savePeerInfo(device.name, targetHost, targetPort, device.id)
        }

        if (wasIncomingInvite) {
            android.util.Log.d("RapiDrop", "pairing.user_accepted")
            val keys = pendingIncomingKeys
            pendingIncomingKeys = null
            keys?.let {
                socketClient.setSessionKey(it.sessionKey)
                preferencesManager.savePairedKey(CryptoEngine.bytesToHex(it.sessionKey.encoded))
            }
            if (targetHost.isNotBlank() && targetPort > 0) {
                serviceScope.launch {
                    android.util.Log.d("RapiDrop", "pairing.accept_sent")
                    socketClient.sendPairAccept(targetHost, targetPort, pendingAcceptPayload)
                    val connectPort = if (targetPort == WireFrame.DEFAULT_CLIENT_PORT) WireFrame.DEFAULT_PORT else targetPort
                    delay(100L)
                    socketClient.connect(targetHost, connectPort)
                }
            }
        } else {
            android.util.Log.d("RapiDrop", "connection.persistent_connecting")
            if (targetHost.isNotBlank() && targetPort > 0) {
                serviceScope.launch {
                    delay(50L)
                    socketClient.connect(targetHost, targetPort)
                }
            }
        }
    }

    fun sendPairInvite(device: DiscoveredDevice, pin: String) {
        pairingTimeoutJob?.cancel()
        val clean = pin.filter { it.isDigit() }.take(6)
        outgoingPairInvite.value = PairInviteInfo(device.name, clean, device.host, device.port)
        pairingError.value = null
        android.util.Log.d("RapiDrop", "pairing.invite_sent")
        serviceScope.launch {
            socketClient.sendPairInvite(device.host, device.port, clean)
        }
        pairingTimeoutJob = serviceScope.launch {
            delay(30000L)
            if (outgoingPairInvite.value?.deviceName == device.name) {
                cancelOutgoingPairInvite()
            }
        }
    }
    fun cancelOutgoingPairInvite() {
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        pendingOutgoingKeys = null
        val pending = outgoingPairInvite.value
        outgoingPairInvite.value = null
        pairingError.value = null
        if (pending != null && pending.host != null) {
            serviceScope.launch {
                socketClient.sendPairDecline(pending.host, pending.port, "CANCELLED")
            }
        }
    }

    fun declineIncomingPairInvite(invite: PairInviteInfo) {
        incomingPairInvite.value = null
        getSystemService(NotificationManager::class.java)?.cancel(PAIRING_NOTIFICATION_ID)
        if (invite.host != null) {
            serviceScope.launch {
                socketClient.sendPairDecline(invite.host, invite.port, "DECLINED")
            }
        }
    }

    fun unpair() {
        outgoingPairInvite.value = null
        pendingOutgoingKeys = null
        autoReconnectJob?.cancel()
        val savedHost = preferencesManager.getPeerHost()
        val savedPort = preferencesManager.getPeerPort()
        preferencesManager.clearPairedKey()
        preferencesManager.clearPairingPin()
        preferencesManager.clearPeerInfo()
        socketClient.setSessionKey(null)
        pairingPin.value = null
        connectedPeerName.value = null
        isConnected.value = false
        connectedDeviceInfo.value = null
        nsdDiscovery.restartDiscovery()
        serviceScope.launch(Dispatchers.IO) {
            socketClient.sendDisconnect()
            if (savedHost != null && savedPort > 0) {
                try {
                    val cleanHost = savedHost.removePrefix("::ffff:")
                    val sock = java.net.Socket()
                    sock.connect(java.net.InetSocketAddress(cleanHost, savedPort), 2000)
                    val frame = com.prabotics.rapidrop.network.WireFrame(
                        type = com.prabotics.rapidrop.network.PacketType.DISCONNECT,
                        nonce = ByteArray(com.prabotics.rapidrop.network.WireFrame.NONCE_SIZE),
                        ciphertext = "UNPAIR".toByteArray(Charsets.UTF_8),
                        tag = ByteArray(com.prabotics.rapidrop.network.WireFrame.AUTH_TAG_SIZE)
                    )
                    sock.getOutputStream().write(frame.serialize())
                    sock.getOutputStream().flush()
                    sock.close()
                } catch (_: Exception) {}
            }
        }
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        return START_STICKY
    }

    fun sendClip(item: ClipItem) {
        guardConnected {
            serviceScope.launch {
                socketClient.sendClip(item)
                addRecentClip(item)
            }
        }
    }

    private inline fun guardConnected(action: () -> Unit) {
        if (isConnected.value && pairingPin.value != null) {
            action()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val foregroundChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "RapiDrop Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows persistent connection state with paired device"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }

            val pairingChannel = NotificationChannel(
                PAIRING_CHANNEL_ID,
                "Pairing Requests",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for incoming device connection and pairing requests"
                enableVibration(true)
                setShowBadge(true)
            }

            manager.createNotificationChannel(foregroundChannel)
            manager.createNotificationChannel(pairingChannel)
        }
    }

    private fun showPairInviteNotification(fromDevice: String, pin: String?) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_PAIR_DEVICE", fromDevice)
            putExtra("EXTRA_PAIR_PIN", pin)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            102,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, PAIRING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pairing Request from $fromDevice")
            .setContentText("$fromDevice wants to connect. Tap to review request.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(PAIRING_NOTIFICATION_ID, notification)
    }

    private fun startInForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.w("RapiDrop", "Failed to startForeground: ${e.message}")
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(FOREGROUND_NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val sendIntent = Intent(this, SendClipActionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        val sendPendingIntent = PendingIntent.getActivity(
            this,
            1,
            sendIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isConnected.value) {
            "Connected to ${connectedPeerName.value ?: "device"}"
        } else {
            "Not connected to any device"
        }

        val builder = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (isConnected.value) {
            builder.addAction(R.drawable.ic_notification, "Push Clipboard", sendPendingIntent)
        }

        return builder.build()
    }

    override fun onDestroy() {
        instance = null
        unregisterNetworkCallback()
        autoReconnectJob?.cancel()
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        releaseWakeLock()
        releaseWifiLock()
        socketServer?.stop()
        socketServer = null
        nsdDiscovery.stopDiscovery()
        nsdDiscovery.unregisterClientService()
        socketClient.disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }
}
