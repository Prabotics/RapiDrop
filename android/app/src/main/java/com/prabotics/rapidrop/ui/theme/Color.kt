package com.prabotics.rapidrop.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color



val BrandBlue = Color(0xFF0082FA)

val PrimaryLight = Color(0xFF0082FA)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFE5F1FF)
val OnPrimaryContainerLight = Color(0xFF004D99)

val PrimaryDark = Color(0xFF389BFF)
val OnPrimaryDark = Color(0xFF002B59)
val PrimaryContainerDark = Color(0xFF004D99)
val OnPrimaryContainerDark = Color(0xFFCCE4FF)
@Immutable
data class StatusColors(
    val connected: Color,
    val onConnected: Color,
    val connectedContainer: Color,
    val onConnectedContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val disconnected: Color,
    val onDisconnected: Color
)

val LightStatusColors = StatusColors(
    connected = Color(0xFF16A34A),
    onConnected = Color(0xFFFFFFFF),
    connectedContainer = Color(0xFFDCFCE7),
    onConnectedContainer = Color(0xFF14532D),
    warning = Color(0xFFD97706),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFF78350F),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    disconnected = Color(0xFF64748B),
    onDisconnected = Color(0xFFFFFFFF)
)

val DarkStatusColors = StatusColors(
    connected = Color(0xFF22C55E),
    onConnected = Color(0xFF052E16),
    connectedContainer = Color(0xFF14532D),
    onConnectedContainer = Color(0xFFDCFCE7),
    warning = Color(0xFFF59E0B),
    onWarning = Color(0xFF451A03),
    warningContainer = Color(0xFF78350F),
    onWarningContainer = Color(0xFFFEF3C7),
    error = Color(0xFFEF4444),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    disconnected = Color(0xFF94A3B8),
    onDisconnected = Color(0xFF0F172A)
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

val LightColorScheme = lightColorScheme(
    primary = Color(0xFF007AEB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8F2FF),
    onPrimaryContainer = Color(0xFF00448A),
    secondary = Color(0xFF4A5568),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDF2F7),
    onSecondaryContainer = Color(0xFF1A202C),
    tertiary = Color(0xFF0284C7),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0F2FE),
    onTertiaryContainer = Color(0xFF0369A1),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1A1D24),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF1A1D24),
    surfaceVariant = Color(0xFFEDF0F5),
    onSurfaceVariant = Color(0xFF5F6675),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF2F4F8),
    surfaceContainerHigh = Color(0xFFE5E9F0),
    surfaceContainerHighest = Color(0xFFD8DEE8),
    outline = Color(0xFF8F97A6),
    outlineVariant = Color(0xFFD4DAE4),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4DA3FF),
    onPrimary = Color(0xFF002952),
    primaryContainer = Color(0xFF0D3B66),
    onPrimaryContainer = Color(0xFFD6E7FF),
    secondary = Color(0xFFB0B6C4),
    onSecondary = Color(0xFF16181F),
    secondaryContainer = Color(0xFF262A34),
    onSecondaryContainer = Color(0xFFDEE3ED),
    tertiary = Color(0xFF38BDF8),
    onTertiary = Color(0xFF082F49),
    tertiaryContainer = Color(0xFF0369A1),
    onTertiaryContainer = Color(0xFFE0F2FE),
    background = Color(0xFF111318),
    onBackground = Color(0xFFF0F2F6),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFF0F2F6),
    surfaceVariant = Color(0xFF1B1E26),
    onSurfaceVariant = Color(0xFF989EAB),
    surfaceContainerLowest = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF161920),
    surfaceContainer = Color(0xFF1C1F27),
    surfaceContainerHigh = Color(0xFF242832),
    surfaceContainerHighest = Color(0xFF2E333E),
    outline = Color(0xFF787E8E),
    outlineVariant = Color(0xFF2B2F3A),
    error = Color(0xFFEF4444),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2)
)
