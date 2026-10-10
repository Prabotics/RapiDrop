package com.prabotics.rapidrop.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Battery3Bar
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DesktopMac
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tablet
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prabotics.rapidrop.clipboard.ClipContentType
import com.prabotics.rapidrop.clipboard.ClipItem
import com.prabotics.rapidrop.clipboard.ConnectedDeviceInfo
import com.prabotics.rapidrop.clipboard.ImageMetadataHelper
import com.prabotics.rapidrop.ui.HapticManager
import com.prabotics.rapidrop.ui.SyncState
import com.prabotics.rapidrop.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PairedDeviceContent(
    isConnected: Boolean,
    isConnecting: Boolean = false,
    deviceInfo: ConnectedDeviceInfo?,
    syncState: SyncState,
    onSendMediaClick: () -> Unit,
    onSendClipboardClick: () -> Unit,
    onUnpairClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showUnpairDialog by remember { mutableStateOf(false) }

    val rawModel = deviceInfo?.modelName?.takeIf { it.isNotBlank() }
    val rawDevice = deviceInfo?.deviceName?.takeIf { it.isNotBlank() }
    val deviceName = rawDevice ?: rawModel ?: "Paired Device"
    val modelName = rawModel ?: deviceName

    val deviceIcon = when {
        modelName.contains("iMac", ignoreCase = true) -> Icons.Rounded.DesktopMac
        modelName.contains("mini", ignoreCase = true) || modelName.contains("Studio", ignoreCase = true) || modelName.contains("PC", ignoreCase = true) -> Icons.Rounded.Computer
        modelName.contains("tablet", ignoreCase = true) || modelName.contains("pad", ignoreCase = true) -> Icons.Rounded.Tablet
        modelName.contains("Phone", ignoreCase = true) || modelName.contains("Pixel", ignoreCase = true) || modelName.contains("Galaxy", ignoreCase = true) -> Icons.Rounded.Smartphone
        else -> Icons.Rounded.Laptop
    }

    val statusColors = LocalStatusColors.current

    val animatedBadgeBg by animateColorAsState(
        targetValue = when {
            isConnected -> statusColors.connected.copy(alpha = 0.12f)
            isConnecting -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = spring(stiffness = 500f),
        label = "statusBadgeBgAnimation"
    )
    val animatedBadgeTint by animateColorAsState(
        targetValue = when {
            isConnected -> statusColors.connected
            isConnecting -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(stiffness = 500f),
        label = "statusBadgeTintAnimation"
    )

    if (showUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.LinkOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Unpair $deviceName?",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                )
            },
            text = {
                Text(
                    text = "You will need to pair again to transfer files or sync clipboard with this device.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnpairDialog = false
                        HapticManager.performClick(context)
                        onUnpairClick()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        text = "Unpair",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUnpairDialog = false }
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = deviceIcon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = deviceName,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(
                                            if (isConnected) statusColors.connected else MaterialTheme.colorScheme.onSurfaceVariant,
                                            CircleShape
                                        )
                                )
                                Text(
                                    text = if (isConnected) "Connected on Wi-Fi" else "Offline",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (deviceInfo?.batteryLevel != null && deviceInfo.batteryLevel in 0..100) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val bIcon = if (deviceInfo.isCharging) {
                                    Icons.Rounded.BatteryChargingFull
                                } else {
                                    batteryIcon(deviceInfo.batteryLevel)
                                }
                                val iconColor = if (deviceInfo.isCharging) {
                                    statusColors.connected
                                } else if (deviceInfo.batteryLevel < 20) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Icon(
                                    imageVector = bIcon,
                                    contentDescription = null,
                                    tint = iconColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "${deviceInfo.batteryLevel}%",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            HapticManager.performClick(context)
                            onSendClipboardClick()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        enabled = isConnected
                    ) {
                        AnimatedContent(
                            targetState = syncState,
                            transitionSpec = { fadeIn(tween(140)).togetherWith(fadeOut(tween(100))) },
                            label = "sendClipButtonState"
                        ) { state ->
                            when (state) {
                                SyncState.SYNCING -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(13.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "Sending...",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp
                                            )
                                        )
                                    }
                                }
                                SyncState.SUCCESS -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = statusColors.connected,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = "Sent",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp
                                            )
                                        )
                                    }
                                }
                                SyncState.IDLE -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ContentCopy,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = "Send Clip",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            HapticManager.performClick(context)
                            onSendMediaClick()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        enabled = isConnected
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Send Files",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        )
                    }
                }
            }
    }
}

private fun batteryIcon(level: Int): ImageVector {
    return when {
        level < 20 -> Icons.Rounded.BatteryAlert
        level < 50 -> Icons.Rounded.Battery3Bar
        level < 85 -> Icons.Rounded.Battery5Bar
        else -> Icons.Rounded.BatteryFull
    }
}
