package com.prabotics.rapidrop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Tablet
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.prabotics.rapidrop.network.DiscoveredDevice
import com.prabotics.rapidrop.ui.HapticManager



@Composable
fun DeviceDiscoveryRadar(
    discoveredDevices: List<DiscoveredDevice>,
    pairingDeviceName: String? = null,
    pairingSasCode: String? = null,
    onDeviceSelect: (DiscoveredDevice) -> Unit,
    onCancelPairing: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AnimatedVisibility(
            visible = pairingDeviceName != null,
            enter = expandVertically(spring(stiffness = 600f, dampingRatio = 0.8f)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = 600f, dampingRatio = 0.8f)) + fadeOut()
        ) {
            pairingDeviceName?.let { deviceName ->
                OutgoingPairingCard(
                    deviceName = deviceName,
                    pin = pairingSasCode,
                    onCancel = onCancelPairing
                )
            }
        }

        if (discoveredDevices.isEmpty() && pairingDeviceName == null) {
            EmptyDiscoveryState()
        } else if (discoveredDevices.isNotEmpty()) {
            NearbyDevicesSection(
                devices = discoveredDevices,
                pairingDeviceName = pairingDeviceName,
                onDeviceSelect = onDeviceSelect
            )
        }
    }
}

@Composable
fun NearbyDevicesSection(
    devices: List<DiscoveredDevice>,
    pairingDeviceName: String? = null,
    onDeviceSelect: (DiscoveredDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Nearby devices",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow
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
                        }
                    )
                    if (index < devices.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OutgoingPairingCard(
    deviceName: String,
    pin: String? = null,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(spring(stiffness = 500f, dampingRatio = 0.8f)),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Connecting to $deviceName",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Waiting for approval on device...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val formattedSas = if (pin != null && pin.length == 6) {
                "${pin.take(3)} ${pin.takeLast(3)}"
            } else pin

            AnimatedVisibility(
                visible = !formattedSas.isNullOrBlank(),
                enter = expandVertically(spring(stiffness = 500f, dampingRatio = 0.8f)) + fadeIn(spring(stiffness = 500f, dampingRatio = 0.8f)),
                exit = shrinkVertically(spring(stiffness = 500f, dampingRatio = 0.8f)) + fadeOut(spring(stiffness = 500f, dampingRatio = 0.8f))
            ) {
                formattedSas?.let { sas ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Verification code",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = sas,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    HapticManager.performClick(context)
                    onCancel()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "Cancel", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun DiscoveredDeviceRow(
    device: DiscoveredDevice,
    isPairing: Boolean,
    onConnectClick: () -> Unit
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
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = deviceIcon,
                contentDescription = null,
                tint = if (isPairing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isPairing) "Connecting..." else "Available on Wi-Fi",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isPairing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        FilledTonalButton(
            onClick = onConnectClick,
            enabled = !isPairing,
            modifier = Modifier
                .height(38.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = "Connect to $displayName"
                },
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = "Connect",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }
    }
}

@Composable
private fun EmptyDiscoveryState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Devices,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp)
            )

            Text(
                text = "No nearby devices",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Make sure RapiDrop is open on your other device and connected to the same Wi-Fi network.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
