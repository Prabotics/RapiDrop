package com.prabotics.rapidrop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
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
import com.prabotics.rapidrop.ui.components.PairedDeviceContent
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.ui.text.style.TextOverflow
import com.prabotics.rapidrop.service.TransferProgress
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

enum class MainNavDestination(val label: String, val icon: ImageVector) {
    DEVICES("Devices", Icons.Rounded.Devices),
    HISTORY("History", Icons.Rounded.History),
    SETTINGS("Settings", Icons.Rounded.Settings)
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
    val incomingPairInvite: PairInviteInfo? = null,
    val outgoingPairInvite: PairInviteInfo? = null,
    val pairingError: String? = null
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
    val onRequestBatteryExemption: () -> Unit = {}
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
        incomingPairInvite,
        outgoingPairInvite,
        pairingError
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
        onRequestBatteryExemption
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
    var currentDestination by remember { mutableStateOf(MainNavDestination.DEVICES) }
    var inspectingClip by remember { mutableStateOf<ClipItem?>(null) }

    BackHandler(enabled = inspectingClip != null || outgoingPairInvite != null || currentDestination != MainNavDestination.DEVICES) {
        if (inspectingClip != null) {
            inspectingClip = null
        } else if (outgoingPairInvite != null) {
            onCancelOutgoingInvite()
        } else if (currentDestination != MainNavDestination.DEVICES) {
            currentDestination = MainNavDestination.DEVICES
        }
    }

    val configuration = LocalConfiguration.current
    val isWideLayout = configuration.screenWidthDp >= 600

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                @OptIn(ExperimentalMaterial3Api::class)
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (currentDestination == MainNavDestination.DEVICES) {
                                RapiDropBrandLogo(size = 28.dp)
                            }
                            Text(
                                text = when (currentDestination) {
                                    MainNavDestination.DEVICES -> "RapiDrop"
                                    MainNavDestination.HISTORY -> "History"
                                    MainNavDestination.SETTINGS -> "Settings"
                                },
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 24.sp
                                )
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            bottomBar = {
                if (!isWideLayout) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 0.dp
                    ) {
                        MainNavDestination.entries.forEach { destination ->
                            val isSelected = currentDestination == destination
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = {
                                    HapticManager.performClick(context)
                                    currentDestination = destination
                                },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.label
                                    )
                                },
                                label = {
                                    Text(
                                        text = destination.label,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (isWideLayout) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Spacer(Modifier.weight(1f))
                        MainNavDestination.entries.forEach { destination ->
                            val isSelected = currentDestination == destination
                            NavigationRailItem(
                                selected = isSelected,
                                onClick = {
                                    HapticManager.performClick(context)
                                    currentDestination = destination
                                },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.label
                                    )
                                },
                                label = {
                                    Text(
                                        text = destination.label,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter
                    ) {
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
                        subtitle = "Enable notifications to view live sync status in background",
                        actionLabel = "Grant",
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
                        subtitle = "Allow unrestricted activity for instant background sync",
                        actionLabel = "Enable 24/7",
                        onActionClick = onRequestBatteryExemption,
                        onDismiss = { batteryDismissed = true }
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

            val deviceAndTransferContent: @Composable () -> Unit = {
                AnimatedContent(
                    targetState = isPaired,
                    transitionSpec = {
                        (fadeIn(spring(stiffness = 600f, dampingRatio = 0.78f)) +
                            expandVertically(spring(stiffness = 600f, dampingRatio = 0.78f)))
                            .togetherWith(
                                fadeOut(spring(stiffness = 600f, dampingRatio = 0.78f)) +
                                    shrinkVertically(spring(stiffness = 600f, dampingRatio = 0.78f))
                            )
                    },
                    label = "connectionStateTransition"
                ) { paired ->
                    if (paired) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            PairedDeviceContent(
                                isConnected = isConnected,
                                isConnecting = isConnecting,
                                deviceInfo = effectiveDeviceInfo,
                                syncState = syncState,
                                recentClips = recentClips,
                                onSendMediaClick = onSendMediaClick,
                                onSendClipboardClick = onSendClipboardClick,
                                onOpenHistoryClick = { currentDestination = MainNavDestination.HISTORY },
                                onCopyClip = onCopyClip,
                                onInspectClip = { clip -> inspectingClip = clip },
                                onUnpairClick = onUnpair
                            )
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
                        }
                    } else {
                        DeviceDiscoveryRadar(
                            discoveredDevices = discoveredDevices,
                            pairingDeviceName = outgoingPairInvite?.deviceName,
                            pairingSasCode = outgoingPairInvite?.pin,
                            onDeviceSelect = { device ->
                                onSendPairInvite(device, "")
                            },
                            onCancelPairing = onCancelOutgoingInvite
                        )
                    }
                }
            }

            val historyFeedContent: @Composable () -> Unit = {
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

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp)
                        .padding(horizontal = horizontalPad, vertical = 16.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    alertsContent()
                    AnimatedContent(
                        targetState = currentDestination,
                        transitionSpec = {
                            if (targetState.ordinal > initialState.ordinal) {
                                (slideInHorizontally(animationSpec = spring(stiffness = 500f, dampingRatio = 0.85f)) { it / 6 } +
                                    fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)))
                                    .togetherWith(
                                        slideOutHorizontally(animationSpec = spring(stiffness = 500f, dampingRatio = 0.85f)) { -it / 6 } +
                                            fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing))
                                    )
                            } else {
                                (slideInHorizontally(animationSpec = spring(stiffness = 500f, dampingRatio = 0.85f)) { -it / 6 } +
                                    fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)))
                                    .togetherWith(
                                        slideOutHorizontally(animationSpec = spring(stiffness = 500f, dampingRatio = 0.85f)) { it / 6 } +
                                            fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing))
                                    )
                            }
                        },
                        label = "mainNavDestinationTransition"
                    ) { destination ->
                        when (destination) {
                            MainNavDestination.DEVICES -> {
                                deviceAndTransferContent()
                            }
                            MainNavDestination.HISTORY -> {
                                historyFeedContent()
                            }
                            MainNavDestination.SETTINGS -> {
                                SettingsContent(
                                    currentMode = mediaDestinationMode,
                                    currentThemeMode = appThemeMode,
                                    showSyncHistory = showSyncHistory,
                                    onModeSelected = onMediaDestinationModeChange,
                                    onThemeModeSelected = onThemeModeChange,
                                    onToggleShowSyncHistory = { _ -> onToggleShowSyncHistory() }
                                )
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
