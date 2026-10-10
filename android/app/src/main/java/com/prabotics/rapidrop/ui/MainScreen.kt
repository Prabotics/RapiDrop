package com.prabotics.rapidrop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import com.prabotics.rapidrop.ui.components.PairedDeviceContent
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import com.prabotics.rapidrop.network.DeviceNameHelper
import com.prabotics.rapidrop.network.PairInviteInfo
import com.prabotics.rapidrop.network.WireFrame
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.prabotics.rapidrop.ui.components.RapiDropBrandLogo
import com.prabotics.rapidrop.ui.components.SettingsContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prabotics.rapidrop.clipboard.ClipContentType
import com.prabotics.rapidrop.clipboard.ClipItem
import com.prabotics.rapidrop.clipboard.ConnectedDeviceInfo
import com.prabotics.rapidrop.clipboard.MediaDestinationMode
import com.prabotics.rapidrop.ui.theme.AppThemeMode
import com.prabotics.rapidrop.ui.theme.LocalStatusColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.ui.text.style.TextOverflow
import com.prabotics.rapidrop.service.TransferProgress
import com.prabotics.rapidrop.service.MirroredNotice
import androidx.compose.foundation.layout.height
import com.prabotics.rapidrop.network.DiscoveredDevice
import com.prabotics.rapidrop.ui.components.DeviceDiscoveryRadar
import com.prabotics.rapidrop.ui.components.NearbyDevicesSection
import com.prabotics.rapidrop.ui.components.PairInviteCard
import com.prabotics.rapidrop.ui.components.PermissionAlertBanner
import com.prabotics.rapidrop.ui.components.FileInspectorDialog
import com.prabotics.rapidrop.ui.components.ImageLightboxDialog
import com.prabotics.rapidrop.ui.components.SyncHistoryFeed
import com.prabotics.rapidrop.ui.components.TextInspectorDialog

enum class MainNavDestination(val label: String) {
    DEVICES("Devices"),
    HISTORY("History"),
    SETTINGS("Settings")
}

enum class CockpitTab(val label: String) {
    HISTORY("Clipboard History"),
    ALERTS("Alerts & OTP")
}

enum class SyncState {
    IDLE,
    SYNCING,
    SUCCESS
}

data class MainScreenState(
    val activeTransferProgress: TransferProgress? = null,
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val deviceInfo: ConnectedDeviceInfo? = null,
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val pinnedClipIds: Set<String> = emptySet(),
    val showSyncHistory: Boolean = true,
    val recentClips: List<ClipItem> = emptyList(),
    val pairingPin: String? = null,
    val connectedPeerName: String? = null,
    val syncState: SyncState = SyncState.IDLE,
    val mediaDestinationMode: MediaDestinationMode = MediaDestinationMode.BOTH,
    val appThemeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val isNotificationGranted: Boolean = false,
    val isBatteryOptimizedIgnored: Boolean = false,
    val isNotificationListenerGranted: Boolean = false,
    val incomingPairInvite: PairInviteInfo? = null,
    val outgoingPairInvite: PairInviteInfo? = null,
    val pairingError: String? = null,
    val recentNotices: List<MirroredNotice> = emptyList()
)

data class MainScreenCallbacks(
    val onCancelTransfer: () -> Unit = {},
    val onCancelOutgoingInvite: () -> Unit = {},
    val onDeclineIncomingInvite: (PairInviteInfo) -> Unit = {},
    val onPairDevice: (DiscoveredDevice, String) -> Unit = { _, _ -> },
    val onSendPairInvite: (DiscoveredDevice, String) -> Unit = { _, _ -> },
    val onUnpair: () -> Unit = {},
    val onSendMediaClick: () -> Unit = {},
    val onSendClipboardClick: () -> Unit = {},
    val onCopyClip: (ClipItem) -> Unit = {},
    val onShareClip: (ClipItem) -> Unit = {},
    val onClearHistory: () -> Unit = {},
    val onDeleteClip: (ClipItem) -> Unit = {},
    val onSendClip: (ClipItem) -> Unit = {},
    val onTogglePinClip: (ClipItem) -> Unit = {},
    val onToggleShowSyncHistory: () -> Unit = {},
    val onMediaDestinationModeChange: (MediaDestinationMode) -> Unit = {},
    val onThemeModeChange: (AppThemeMode) -> Unit = {},
    val onRequestNotificationPermission: () -> Unit = {},
    val onRequestBatteryExemption: () -> Unit = {},
    val onRequestNotificationListener: () -> Unit = {},
    val onCopyOtp: (String) -> Unit = {},
    val onClearNotices: () -> Unit = {}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainScreenState,
    callbacks: MainScreenCallbacks
) {
    val (
        activeTransferProgress,
        isConnected,
        isConnecting,
        deviceInfo,
        discoveredDevices,
        pinnedClipIds,
        showSyncHistory,
        recentClips,
        pairingPin,
        connectedPeerName,
        syncState,
        mediaDestinationMode,
        appThemeMode,
        isNotificationGranted,
        isBatteryOptimizedIgnored,
        isNotificationListenerGranted,
        incomingPairInvite,
        outgoingPairInvite,
        pairingError,
        recentNotices
    ) = state
    val (
        onCancelTransfer,
        onCancelOutgoingInvite,
        onDeclineIncomingInvite,
        onPairDevice,
        onSendPairInvite,
        onUnpair,
        onSendMediaClick,
        onSendClipboardClick,
        onCopyClip,
        onShareClip,
        onClearHistory,
        onDeleteClip,
        onSendClip,
        onTogglePinClip,
        onToggleShowSyncHistory,
        onMediaDestinationModeChange,
        onThemeModeChange,
        onRequestNotificationPermission,
        onRequestBatteryExemption,
        onRequestNotificationListener,
        onCopyOtp,
        onClearNotices
    ) = callbacks
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    val isPaired = !pairingPin.isNullOrEmpty()
    var activePairInvite by remember { mutableStateOf<PairInviteInfo?>(null) }

    LaunchedEffect(incomingPairInvite) {
        if (!isPaired && !isConnected) {
            activePairInvite = incomingPairInvite
        }
    }
    LaunchedEffect(isConnected, isPaired) {
        if (isConnected || isPaired) {
            activePairInvite = null
        }
    }
    var notificationsDismissed by remember { mutableStateOf(false) }
    var batteryDismissed by remember { mutableStateOf(false) }
    var listenerDismissed by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var activeCockpitTab by remember { mutableStateOf(CockpitTab.HISTORY) }
    var inspectingClip by remember { mutableStateOf<ClipItem?>(null) }

    BackHandler(enabled = inspectingClip != null || outgoingPairInvite != null || showSettings) {
        if (inspectingClip != null) {
            inspectingClip = null
        } else if (outgoingPairInvite != null) {
            onCancelOutgoingInvite()
        } else if (showSettings) {
            showSettings = false
        }
    }

    val configuration = LocalConfiguration.current
    val isWideLayout = configuration.screenWidthDp >= 600
    val horizontalPad = if (configuration.screenWidthDp <= 360) 14.dp else 20.dp

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = horizontalPad, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (!showSettings) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RapiDropBrandLogo(size = 28.dp)
                            Text(
                                text = "RapiDrop",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isConnected || isConnecting) {
                                val statusColors = LocalStatusColors.current
                                Surface(
                                    shape = CircleShape,
                                    color = if (isConnected) statusColors.connected.copy(alpha = 0.15f)
                                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(
                                                    if (isConnected) statusColors.connected else MaterialTheme.colorScheme.primary,
                                                    CircleShape
                                                )
                                        )
                                        Text(
                                            text = if (isConnected) "Connected" else "Connecting",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = if (isConnected) statusColors.connected else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }

                        Surface(
                            onClick = {
                                HapticManager.performClick(context)
                                showSettings = true
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                onClick = {
                                    HapticManager.performClick(context)
                                    showSettings = false
                                },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                        contentDescription = "Back",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Settings",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.TopCenter
            ) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val containerHeight = maxHeight
                    val horizontalPad = if (maxWidth <= 360.dp) 14.dp else 20.dp

                    val effectiveDeviceInfo = deviceInfo ?: if (isPaired && connectedPeerName != null) {
                        ConnectedDeviceInfo(
                            modelId = "",
                            modelName = connectedPeerName,
                            chip = "",
                            deviceName = connectedPeerName
                        )
                    } else null

                    val alertsContent: @Composable ColumnScope.() -> Unit = {
                        AnimatedVisibility(
                            visible = !isNotificationGranted && !notificationsDismissed,
                            enter = expandVertically(spring(stiffness = 600f, dampingRatio = 0.78f)) + fadeIn(spring(stiffness = 600f, dampingRatio = 0.78f)),
                            exit = shrinkVertically(spring(stiffness = 600f, dampingRatio = 0.78f)) + fadeOut(spring(stiffness = 600f, dampingRatio = 0.78f))
                        ) {
                            PermissionAlertBanner(
                                title = "Notifications disabled",
                                actionLabel = "Enable",
                                onActionClick = onRequestNotificationPermission,
                                onDismiss = { notificationsDismissed = true }
                            )
                        }

                        AnimatedVisibility(
                            visible = isNotificationGranted && !isBatteryOptimizedIgnored && !batteryDismissed,
                            enter = expandVertically(spring(stiffness = 600f, dampingRatio = 0.78f)) + fadeIn(spring(stiffness = 600f, dampingRatio = 0.78f)),
                            exit = shrinkVertically(spring(stiffness = 600f, dampingRatio = 0.78f)) + fadeOut(spring(stiffness = 600f, dampingRatio = 0.78f))
                        ) {
                            PermissionAlertBanner(
                                title = "Battery optimization active",
                                actionLabel = "Allow",
                                onActionClick = onRequestBatteryExemption,
                                onDismiss = { batteryDismissed = true }
                            )
                        }
                        AnimatedVisibility(
                            visible = isPaired && !isNotificationListenerGranted && !listenerDismissed,
                            enter = expandVertically(spring(stiffness = 600f, dampingRatio = 0.78f)) + fadeIn(spring(stiffness = 600f, dampingRatio = 0.78f)),
                            exit = shrinkVertically(spring(stiffness = 600f, dampingRatio = 0.78f)) + fadeOut(spring(stiffness = 600f, dampingRatio = 0.78f))
                        ) {
                            PermissionAlertBanner(
                                title = "Notification mirroring off",
                                actionLabel = "Enable",
                                onActionClick = onRequestNotificationListener,
                                onDismiss = { listenerDismissed = true }
                            )
                        }
                        AnimatedVisibility(
                            visible = activeTransferProgress != null,
                            enter = expandVertically(spring(stiffness = 600f, dampingRatio = 0.78f)) + fadeIn(spring(stiffness = 600f, dampingRatio = 0.78f)),
                            exit = shrinkVertically(spring(stiffness = 600f, dampingRatio = 0.78f)) + fadeOut(spring(stiffness = 600f, dampingRatio = 0.78f))
                        ) {
                            activeTransferProgress?.let { progress ->
                                TransferProgressBanner(progress = progress, onCancel = onCancelTransfer)
                            }
                        }

                        AnimatedVisibility(
                            visible = activePairInvite != null && !isPaired,
                            enter = expandVertically(spring(stiffness = 600f, dampingRatio = 0.78f)) + fadeIn(spring(stiffness = 600f, dampingRatio = 0.78f)),
                            exit = shrinkVertically(spring(stiffness = 600f, dampingRatio = 0.78f)) + fadeOut(spring(stiffness = 600f, dampingRatio = 0.78f))
                        ) {
                            activePairInvite?.let { invite ->
                                PairInviteCard(
                                    deviceName = invite.deviceName,
                                    pin = invite.pin,
                                    onAccept = {
                                        val match = discoveredDevices.find { dev ->
                                            DeviceNameHelper.isSameDevice(dev.name, invite.deviceName)
                                        }
                                        val targetHost = invite.host?.takeIf { it.isNotBlank() } ?: match?.host ?: ""
                                        val targetPort = if (invite.port > 0) invite.port else (match?.port ?: WireFrame.DEFAULT_PORT)
                                        val target = match?.copy(host = targetHost, port = targetPort)
                                            ?: DiscoveredDevice(name = invite.deviceName, host = targetHost, port = targetPort)
                                        val pin = if (!invite.pin.isNullOrBlank()) invite.pin else String.format(java.util.Locale.US, "%06d", (100000..999999).random())
                                        onPairDevice(target, pin)
                                        activePairInvite = null
                                    },
                                    onDecline = {
                                        onDeclineIncomingInvite(invite)
                                        activePairInvite = null
                                    }
                                )
                            }
                        }
                    }

                    if (showSettings) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 640.dp)
                                .padding(horizontal = horizontalPad, vertical = 16.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            SettingsContent(
                                currentMode = mediaDestinationMode,
                                currentThemeMode = appThemeMode,
                                showSyncHistory = showSyncHistory,
                                onModeSelected = onMediaDestinationModeChange,
                                onThemeModeSelected = onThemeModeChange,
                                onToggleShowSyncHistory = { _ -> onToggleShowSyncHistory() },
                                onClose = { showSettings = false }
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 640.dp)
                                .padding(horizontal = horizontalPad, vertical = 16.dp)
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            alertsContent()
                            if (isPaired) {
                                PairedDeviceContent(
                                    isConnected = isConnected,
                                    isConnecting = isConnecting,
                                    deviceInfo = effectiveDeviceInfo,
                                    syncState = syncState,
                                    onSendMediaClick = onSendMediaClick,
                                    onSendClipboardClick = onSendClipboardClick,
                                    onUnpairClick = onUnpair
                                )

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Surface(
                                            onClick = {
                                                HapticManager.performClick(context)
                                                activeCockpitTab = CockpitTab.HISTORY
                                            },
                                            shape = RoundedCornerShape(9.dp),
                                            color = if (activeCockpitTab == CockpitTab.HISTORY) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(vertical = 10.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Clipboard History",
                                                    style = MaterialTheme.typography.labelLarge.copy(
                                                        fontWeight = if (activeCockpitTab == CockpitTab.HISTORY) FontWeight.Bold else FontWeight.Medium
                                                    ),
                                                    color = if (activeCockpitTab == CockpitTab.HISTORY) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                if (recentClips.isNotEmpty()) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Surface(
                                                        shape = CircleShape,
                                                        color = if (activeCockpitTab == CockpitTab.HISTORY) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                                                    ) {
                                                        Text(
                                                            text = "${recentClips.size}",
                                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                            color = if (activeCockpitTab == CockpitTab.HISTORY) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Surface(
                                            onClick = {
                                                HapticManager.performClick(context)
                                                activeCockpitTab = CockpitTab.ALERTS
                                            },
                                            shape = RoundedCornerShape(9.dp),
                                            color = if (activeCockpitTab == CockpitTab.ALERTS) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(vertical = 10.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Alerts & OTP",
                                                    style = MaterialTheme.typography.labelLarge.copy(
                                                        fontWeight = if (activeCockpitTab == CockpitTab.ALERTS) FontWeight.Bold else FontWeight.Medium
                                                    ),
                                                    color = if (activeCockpitTab == CockpitTab.ALERTS) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                if (recentNotices.isNotEmpty()) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Surface(
                                                        shape = CircleShape,
                                                        color = if (activeCockpitTab == CockpitTab.ALERTS) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                                                    ) {
                                                        Text(
                                                            text = "${recentNotices.size}",
                                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                            color = if (activeCockpitTab == CockpitTab.ALERTS) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (activeCockpitTab == CockpitTab.HISTORY) {
                                    SyncHistoryFeed(
                                        recentClips = recentClips,
                                        pinnedClipIds = pinnedClipIds,
                                        showSyncHistory = showSyncHistory,
                                        onCopyClip = onCopyClip,
                                        onShareClip = onShareClip,
                                        onClearHistory = onClearHistory,
                                        onDeleteClip = onDeleteClip,
                                        onSendClip = onSendClip,
                                        onTogglePinClip = onTogglePinClip,
                                        onToggleShowSyncHistory = onToggleShowSyncHistory,
                                        onInspectClip = { clip -> inspectingClip = clip }
                                    )
                                } else {
                                    AlertsFeed(
                                        notices = recentNotices,
                                        onCopyOtp = onCopyOtp,
                                        onClear = onClearNotices
                                    )
                                }

                                val otherDiscovered = discoveredDevices.filter {
                                    !DeviceNameHelper.isSameDevice(it.name, connectedPeerName)
                                }
                                if (otherDiscovered.isNotEmpty()) {
                                    NearbyDevicesSection(
                                        devices = otherDiscovered,
                                        pairingDeviceName = outgoingPairInvite?.deviceName,
                                        onDeviceSelect = { device -> onSendPairInvite(device, "") }
                                    )
                                }
                            } else {
                                val isScanningAlone = discoveredDevices.isEmpty() && recentClips.isEmpty()
                                if (isScanningAlone) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = (containerHeight - 160.dp).coerceAtLeast(320.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        DeviceDiscoveryRadar(
                                            discoveredDevices = discoveredDevices,
                                            pairingDeviceName = outgoingPairInvite?.deviceName,
                                            pairingSasCode = outgoingPairInvite?.pin,
                                            onDeviceSelect = { device -> onSendPairInvite(device, "") },
                                            onCancelPairing = onCancelOutgoingInvite
                                        )
                                    }
                                } else {
                                    DeviceDiscoveryRadar(
                                        discoveredDevices = discoveredDevices,
                                        pairingDeviceName = outgoingPairInvite?.deviceName,
                                        pairingSasCode = outgoingPairInvite?.pin,
                                        onDeviceSelect = { device -> onSendPairInvite(device, "") },
                                        onCancelPairing = onCancelOutgoingInvite
                                    )
                                    if (recentClips.isNotEmpty()) {
                                        SyncHistoryFeed(
                                            recentClips = recentClips,
                                            pinnedClipIds = pinnedClipIds,
                                            showSyncHistory = showSyncHistory,
                                            onCopyClip = onCopyClip,
                                            onShareClip = onShareClip,
                                            onClearHistory = onClearHistory,
                                            onDeleteClip = onDeleteClip,
                                            onSendClip = onSendClip,
                                            onTogglePinClip = onTogglePinClip,
                                            onToggleShowSyncHistory = onToggleShowSyncHistory,
                                            onInspectClip = { clip -> inspectingClip = clip }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = inspectingClip != null,
                enter = fadeIn(animationSpec = tween(220)) + slideInVertically(animationSpec = tween(260)) { it / 4 },
                exit = fadeOut(animationSpec = tween(180)) + slideOutVertically(animationSpec = tween(220)) { it / 4 }
            ) {
                inspectingClip?.let { clip ->
                    when (clip.type) {
                        ClipContentType.IMAGE -> ImageLightboxDialog(
                            clip = clip,
                            onDismiss = { inspectingClip = null },
                            onShare = onShareClip,
                            onSendToDevice = onSendClip,
                            onDelete = onDeleteClip
                        )
                        ClipContentType.TEXT, ClipContentType.URL -> TextInspectorDialog(
                            clip = clip,
                            onDismiss = { inspectingClip = null },
                            onCopy = onCopyClip,
                            onShare = onShareClip,
                            onSendToDevice = onSendClip,
                            onDelete = onDeleteClip
                        )
                        ClipContentType.FILE -> FileInspectorDialog(
                            clip = clip,
                            onDismiss = { inspectingClip = null },
                            onShare = onShareClip,
                            onSendToDevice = onSendClip,
                            onDelete = onDeleteClip
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AlertsFeed(
    notices: List<MirroredNotice>,
    onCopyOtp: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Mirrored Alerts (${notices.size})",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (notices.isNotEmpty()) {
                TextButton(
                    onClick = {
                        HapticManager.performClick(context)
                        onClear()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "Clear all",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (notices.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Text(
                        text = "No Mirrored Alerts",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Phone notifications and 1-tap OTP verification codes will appear here in real time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            notices.forEach { notice ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = notice.appName,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            val elapsed = android.text.format.DateUtils.getRelativeTimeSpanString(
                                notice.timestamp,
                                System.currentTimeMillis(),
                                android.text.format.DateUtils.MINUTE_IN_MILLIS,
                                android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE
                            )
                            Text(
                                text = elapsed.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (notice.title.isNotBlank()) {
                            Text(
                                text = notice.title,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (notice.text.isNotBlank()) {
                            Text(
                                text = notice.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!notice.otpCode.isNullOrBlank()) {
                            FilledTonalButton(
                                onClick = {
                                    HapticManager.performSuccess(context)
                                    onCopyOtp(notice.otpCode)
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Copy ${notice.otpCode}",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TransferProgressBanner(
    progress: TransferProgress,
    onCancel: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (progress.isSending) Icons.Rounded.Upload else Icons.Rounded.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (progress.isSending) "Sending ${progress.fileName}" else "Receiving ${progress.fileName}",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (progress.totalFiles > 1) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = "${progress.fileIndex}/${progress.totalFiles}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Cancel transfer",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            val animatedProgress by animateFloatAsState(
                targetValue = progress.progressFraction,
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                label = "transferProgressFraction"
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {}
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val sentMb = progress.bytesTransferred / 1_048_576.0
                val totalMb = progress.totalBytes / 1_048_576.0
                Text(
                    text = String.format(java.util.Locale.US, "%.1f / %.1f MB", sentMb, totalMb),
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
                if (progress.speedBytesPerSec > 0) {
                    val speedMb = progress.speedBytesPerSec / 1_048_576.0
                    val speedText = String.format(java.util.Locale.US, "%.1f MB/s", speedMb)
                    val etaText = progress.formattedEta?.let { ", $it" } ?: ""
                    Text(
                        text = "$speedText$etaText",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            }
        }
    }
}
