package com.prabotics.rapidrop.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.prabotics.rapidrop.clipboard.MediaDestinationMode
import com.prabotics.rapidrop.preference.PreferencesManager
import com.prabotics.rapidrop.service.UpdateChecker
import com.prabotics.rapidrop.service.UpdateResult
import com.prabotics.rapidrop.ui.HapticManager
import com.prabotics.rapidrop.ui.theme.AppThemeMode
import com.prabotics.rapidrop.service.NotificationSyncService
import com.prabotics.rapidrop.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@Composable
fun SettingsContent(
    currentMode: MediaDestinationMode,
    currentThemeMode: AppThemeMode,
    showSyncHistory: Boolean = true,
    onModeSelected: (MediaDestinationMode) -> Unit,
    onThemeModeSelected: (AppThemeMode) -> Unit,
    onToggleShowSyncHistory: (Boolean) -> Unit = {},
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preferencesManager = remember(context) { PreferencesManager(context) }
    val coroutineScope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateResult?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var showReleaseNotesDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showMediaDialog by remember { mutableStateOf(false) }

    var isHapticEnabled by remember { mutableStateOf(preferencesManager.isHapticEnabled()) }
    var isNotificationMirroringEnabled by remember { mutableStateOf(preferencesManager.isNotificationMirroringEnabled()) }
    var isNotificationListenerGranted by remember { mutableStateOf(NotificationSyncService.isPermissionGranted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val hasPermission = NotificationSyncService.isPermissionGranted(context)
                isNotificationListenerGranted = hasPermission
                if (hasPermission && preferencesManager.isNotificationMirroringEnabled()) {
                    isNotificationMirroringEnabled = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val isNotificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val isBatteryOptimizedIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    } else true

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = "Choose Theme",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            },
            text = {
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppThemeMode.entries.forEach { mode ->
                        val isSelected = currentThemeMode == mode
                        val modeIcon = when (mode) {
                            AppThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
                            AppThemeMode.LIGHT -> Icons.Rounded.LightMode
                            AppThemeMode.DARK -> Icons.Rounded.DarkMode
                        }
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = containerColor,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = isSelected,
                                        onClick = {
                                            HapticManager.performClick(context)
                                            onThemeModeSelected(mode)
                                            showThemeDialog = false
                                        },
                                        role = Role.RadioButton
                                    )
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Icon(
                                        imageVector = modeIcon,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(
                                        text = mode.displayLabel,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                        ),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text(
                        text = "Cancel",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    if (showMediaDialog) {
        AlertDialog(
            onDismissRequest = { showMediaDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = "Media Destination",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            },
            text = {
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MediaDestinationMode.entries.forEach { mode ->
                        val isSelected = currentMode == mode
                        val modeIcon = when (mode) {
                            MediaDestinationMode.BOTH -> Icons.Rounded.FolderCopy
                            MediaDestinationMode.FOLDER_ONLY -> Icons.Rounded.Folder
                            MediaDestinationMode.CLIPBOARD_ONLY -> Icons.Rounded.ContentPaste
                        }
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = containerColor,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = isSelected,
                                        onClick = {
                                            HapticManager.performClick(context)
                                            onModeSelected(mode)
                                            showMediaDialog = false
                                        },
                                        role = Role.RadioButton
                                    )
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Icon(
                                        imageVector = modeIcon,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = mode.displayLabel,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                            ),
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = mode.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMediaDialog = false }) {
                    Text(
                        text = "Cancel",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(top = 8.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val cardBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

        SettingsGroupHeader(title = "Sync & Transfer")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = cardBorder
        ) {
            Column {
                SettingsNavigationRow(
                    icon = Icons.Rounded.Folder,
                    title = "Media Destination",
                    subtitle = currentMode.displayLabel,
                    onClick = { showMediaDialog = true },
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp, end = 16.dp), color = dividerColor)
                SettingsSwitchRow(
                    icon = Icons.Rounded.History,
                    title = "Sync History",
                    subtitle = "Save clipboard history on device",
                    checked = showSyncHistory,
                    onCheckedChange = {
                        onToggleShowSyncHistory(it)
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp, end = 16.dp), color = dividerColor)
                SettingsSwitchRow(
                    icon = Icons.Rounded.Notifications,
                    title = "Mirror Notifications",
                    subtitle = "Forward phone alerts to Mac",
                    checked = isNotificationMirroringEnabled && isNotificationListenerGranted,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            preferencesManager.setNotificationMirroringEnabled(true)
                            isNotificationMirroringEnabled = true
                            if (!isNotificationListenerGranted) {
                                NotificationSyncService.openSettings(context)
                            }
                        } else {
                            isNotificationMirroringEnabled = false
                            preferencesManager.setNotificationMirroringEnabled(false)
                        }
                    },
                    shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
                )
            }
        }

        SettingsGroupHeader(title = "General")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = cardBorder
        ) {
            Column {
                SettingsNavigationRow(
                    icon = Icons.Rounded.Palette,
                    title = "Theme",
                    subtitle = currentThemeMode.displayLabel,
                    onClick = { showThemeDialog = true },
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp, end = 16.dp), color = dividerColor)
                SettingsSwitchRow(
                    icon = Icons.Rounded.Vibration,
                    title = "Haptic Feedback",
                    subtitle = "Vibrate on touch and transfers",
                    checked = isHapticEnabled,
                    onCheckedChange = {
                        isHapticEnabled = it
                        preferencesManager.setHapticEnabled(it)
                    },
                    shape = if (isBatteryOptimizedIgnored) RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp) else RectangleShape
                )
                if (!isBatteryOptimizedIgnored) {
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp, end = 16.dp), color = dividerColor)
                    SettingsPermissionRow(
                        icon = Icons.Rounded.BatteryChargingFull,
                        title = "Battery Optimization",
                        subtitle = "Allow uninterrupted background sync",
                        isGranted = false,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                try {
                                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }
                        },
                        shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
                    )
                }
            }
        }

        SettingsGroupHeader(title = "About")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = cardBorder
        ) {
                Column {
                    val currentUpdate = updateState
                    val isUpdateAvailable = currentUpdate is UpdateResult.UpdateAvailable
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                            .clickable(enabled = !isCheckingUpdate) {
                                if (isUpdateAvailable) {
                                    HapticManager.performClick(context)
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse((currentUpdate as UpdateResult.UpdateAvailable).releaseUrl))
                                    context.startActivity(intent)
                                } else {
                                    HapticManager.performClick(context)
                                    isCheckingUpdate = true
                                    coroutineScope.launch {
                                        val startTime = System.currentTimeMillis()
                                        val res = UpdateChecker.checkForUpdates(context)
                                        val elapsed = System.currentTimeMillis() - startTime
                                        if (elapsed < 500L) {
                                            delay(500L - elapsed)
                                        }
                                        updateState = res
                                        isCheckingUpdate = false
                                        if (res is UpdateResult.UpdateAvailable) {
                                            HapticManager.performSuccess(context)
                                        }
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            RapiDropBrandLogo(size = 24.dp)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "RapiDrop",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitleText = when {
                                    isCheckingUpdate -> "Checking for updates..."
                                    isUpdateAvailable -> "Update available, tap to view"
                                    currentUpdate is UpdateResult.UpToDate -> "Up to date"
                                    currentUpdate is UpdateResult.Error -> "Check failed"
                                    else -> "Tap to check for updates"
                                }
                                Crossfade(
                                    targetState = subtitleText,
                                    animationSpec = tween(220)
                                ) { text ->
                                    Text(
                                        text = text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isUpdateAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (isUpdateAvailable && (currentUpdate as UpdateResult.UpdateAvailable).releaseNotes.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        HapticManager.performClick(context)
                                        showReleaseNotesDialog = true
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Description,
                                        contentDescription = "Release Notes",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Crossfade(
                                targetState = isCheckingUpdate,
                                animationSpec = tween(220)
                            ) { checking ->
                                if (checking) {
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 14.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else {
                                    val pillColor = if (isUpdateAvailable) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                                    val pillTextColor = if (isUpdateAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    val versionText = if (isUpdateAvailable) {
                                        "v${(currentUpdate as UpdateResult.UpdateAvailable).latestVersion}"
                                    } else {
                                        "v${UpdateChecker.CURRENT_VERSION}"
                                    }
                                    Surface(
                                        shape = CircleShape,
                                        color = pillColor
                                    ) {
                                        Text(
                                            text = versionText,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = pillTextColor,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                    SettingsExternalLinkRow(
                        painter = painterResource(id = R.drawable.ic_github),
                        title = "GitHub",
                        subtitle = "github.com/Prabotics/RapiDrop",
                        onClick = {
                            HapticManager.performClick(context)
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Prabotics/RapiDrop"))
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
                    )
                }
        }
    }

    if (showReleaseNotesDialog && updateState is UpdateResult.UpdateAvailable) {
        val u = updateState as UpdateResult.UpdateAvailable
        Dialog(onDismissRequest = { showReleaseNotesDialog = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "What's New in ${u.latestVersion}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = u.releaseNotes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showReleaseNotesDialog = false }) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        ),
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsNavigationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    shape: Shape = RectangleShape
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable {
                HapticManager.performClick(context)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape = RectangleShape
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = {
                    HapticManager.performClick(context)
                    onCheckedChange(it)
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        )
    }
}

@Composable
private fun SettingsPermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isGranted: Boolean,
    onClick: () -> Unit,
    shape: Shape = RectangleShape
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Text(
                text = if (isGranted) "Allowed" else "Optimized",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun SettingsExternalLinkRow(
    painter: Painter,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    shape: Shape = RectangleShape
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SettingsExternalLinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    shape: Shape = RectangleShape
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp)
        )
    }
}
