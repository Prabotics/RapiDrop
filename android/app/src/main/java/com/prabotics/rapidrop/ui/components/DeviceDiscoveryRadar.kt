package com.prabotics.rapidrop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tablet
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prabotics.rapidrop.network.DeviceNameHelper
import com.prabotics.rapidrop.network.DiscoveredDevice
import com.prabotics.rapidrop.ui.HapticManager
import com.prabotics.rapidrop.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

@Composable
fun DeviceDiscoveryRadar(
    discoveredDevices: List<DiscoveredDevice>,
    pairingDeviceName: String? = null,
    pairingSasCode: String? = null,
    onDeviceSelect: (DiscoveredDevice) -> Unit,
    onCancelPairing: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (pairingDeviceName != null) {
        OutgoingPairingBottomSheet(
            deviceName = pairingDeviceName,
            pin = pairingSasCode,
            onCancel = onCancelPairing,
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ScanningHeroStage()

        if (discoveredDevices.isNotEmpty()) {
            NearbyDevicesSection(
                devices = discoveredDevices,
                pairingDeviceName = pairingDeviceName,
                onDeviceSelect = onDeviceSelect,
            )
        }
    }
}

@Composable
private fun ScanningHeroStage() {
    val context = LocalContext.current
    val deviceName = DeviceNameHelper.getDeviceFriendlyName(context)
    val primaryColor = MaterialTheme.colorScheme.primary

    val infiniteTransition = rememberInfiniteTransition(label = "radarPulse")

    val pulse1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "p1",
    )
    val pulse2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, delayMillis = 1066, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "p2",
    )
    val pulse3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, delayMillis = 2133, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "p3",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Box(
            modifier = Modifier.size(180.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(180.dp)) {
                val centerOffset = center
                val baseRadius = 44.dp.toPx()
                val maxRadius = 80.dp.toPx()
                val strokeWidth = 1.8.dp.toPx()
                listOf(pulse1, pulse2, pulse3).forEach { p ->
                    val currentRadius = baseRadius + (maxRadius - baseRadius) * p
                    val alpha = (1f - p).coerceIn(0f, 1f) * 0.75f
                    drawCircle(
                        color = primaryColor.copy(alpha = alpha),
                        radius = currentRadius,
                        center = centerOffset,
                        style = Stroke(width = strokeWidth),
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(80.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Smartphone,
                        contentDescription = null,
                        tint = primaryColor,
                        modifier = Modifier.size(38.dp),
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = deviceName,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Visible to nearby devices",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun NearbyDevicesSection(
    devices: List<DiscoveredDevice>,
    pairingDeviceName: String? = null,
    onDeviceSelect: (DiscoveredDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Nearby devices",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        ) {
            Column {
                devices.forEachIndexed { index, device ->
                    val isPairing = pairingDeviceName != null && (device.name == pairingDeviceName || device.name.contains(pairingDeviceName) || pairingDeviceName.contains(device.name))
                    DiscoveredDeviceRow(
                        device = device,
                        isPairing = isPairing,
                        onConnectClick = {
                            HapticManager.performClick(context)
                            onDeviceSelect(device)
                        },
                    )
                    if (index < devices.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredDeviceRow(
    device: DiscoveredDevice,
    isPairing: Boolean,
    onConnectClick: () -> Unit,
) {
    val displayName = device.name
    val deviceIcon = when {
        device.name.contains("mac", ignoreCase = true) || device.name.contains("book", ignoreCase = true) -> Icons.Rounded.Laptop
        device.name.contains("pc", ignoreCase = true) || device.name.contains("windows", ignoreCase = true) -> Icons.Rounded.Computer
        device.name.contains("tablet", ignoreCase = true) || device.name.contains("pad", ignoreCase = true) -> Icons.Rounded.Tablet
        else -> Icons.Rounded.PhoneAndroid
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isPairing, onClick = onConnectClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = deviceIcon,
                        contentDescription = null,
                        tint = if (isPairing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isPairing) "Connecting..." else "Ready to connect",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPairing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        FilledTonalButton(
            onClick = onConnectClick,
            enabled = !isPairing,
            modifier = Modifier
                .height(36.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = "Connect to $displayName"
                },
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                text = "Connect",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutgoingPairingBottomSheet(
    deviceName: String,
    pin: String? = null,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val deviceIcon = when {
        deviceName.contains("mac", ignoreCase = true) || deviceName.contains("book", ignoreCase = true) -> Icons.Rounded.Laptop
        deviceName.contains("pc", ignoreCase = true) || deviceName.contains("windows", ignoreCase = true) -> Icons.Rounded.Computer
        deviceName.contains("tablet", ignoreCase = true) || deviceName.contains("pad", ignoreCase = true) -> Icons.Rounded.Tablet
        else -> Icons.Rounded.PhoneAndroid
    }

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = deviceIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Waiting for approval",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!pin.isNullOrBlank()) {
                val cleanPin = pin.filter { it.isDigit() }
                Row(
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        cleanPin.take(3).forEach { digit ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                modifier = Modifier.size(width = 40.dp, height = 52.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = digit.toString(),
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 22.sp,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }

                    if (cleanPin.length >= 6) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            cleanPin.drop(3).take(3).forEach { digit ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                    modifier = Modifier.size(width = 40.dp, height = 52.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = digit.toString(),
                                            style = MaterialTheme.typography.headlineMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 22.sp,
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            var progressTarget by remember(pin) { mutableFloatStateOf(1f) }
            var hasWarnedHaptic by remember(pin) { mutableStateOf(false) }

            LaunchedEffect(pin) {
                if (!pin.isNullOrBlank()) {
                    progressTarget = 0f
                }
            }
            val remainingProgress by animateFloatAsState(
                targetValue = progressTarget,
                animationSpec = tween(durationMillis = 30000, easing = LinearEasing),
                label = "expiryProgress",
            )

            val statusColors = LocalStatusColors.current
            val barColor by animateColorAsState(
                targetValue = when {
                    remainingProgress <= 0.13f -> MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
                    remainingProgress <= 0.27f -> statusColors.warning.copy(alpha = 0.22f)
                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                },
                animationSpec = tween(400),
                label = "expiryColor",
            )

            LaunchedEffect(remainingProgress) {
                if (remainingProgress <= 0.13f && !hasWarnedHaptic) {
                    hasWarnedHaptic = true
                    HapticManager.performWarning(context)
                }
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clickable {
                        HapticManager.performClick(context)
                        coroutineScope.launch {
                            try {
                                sheetState.hide()
                            } finally {
                                onCancel()
                            }
                        }
                    },
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (!pin.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = remainingProgress)
                                .fillMaxHeight()
                                .background(
                                    color = barColor,
                                    shape = RoundedCornerShape(22.dp),
                                ),
                        )
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
