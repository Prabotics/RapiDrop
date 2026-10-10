package com.prabotics.rapidrop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import com.prabotics.rapidrop.ui.theme.LocalStatusColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Tablet
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.prabotics.rapidrop.ui.HapticManager

@Composable
fun PairInviteCard(
    deviceName: String,
    pin: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val formattedPin = if (!pin.isNullOrBlank() && pin.length == 6) {
        "${pin.substring(0, 3)} ${pin.substring(3)}"
    } else {
        pin
    }

    val deviceIcon = when {
        deviceName.contains("mac", ignoreCase = true) || deviceName.contains("book", ignoreCase = true) -> Icons.Rounded.Laptop
        deviceName.contains("pc", ignoreCase = true) || deviceName.contains("windows", ignoreCase = true) -> Icons.Rounded.Computer
        deviceName.contains("tablet", ignoreCase = true) || deviceName.contains("pad", ignoreCase = true) -> Icons.Rounded.Tablet
        else -> Icons.Rounded.PhoneAndroid
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(spring(stiffness = 500f, dampingRatio = 0.8f)),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = deviceIcon,
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
                        text = deviceName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Wants to pair with this device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(
                visible = !formattedPin.isNullOrBlank(),
                enter = expandVertically(spring(stiffness = 500f, dampingRatio = 0.8f)) + fadeIn(spring(stiffness = 500f, dampingRatio = 0.8f)),
                exit = shrinkVertically(spring(stiffness = 500f, dampingRatio = 0.8f)) + fadeOut(spring(stiffness = 500f, dampingRatio = 0.8f))
            ) {
                formattedPin?.let { pinText ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Verification code",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = pinText,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            var progressTarget by remember { mutableFloatStateOf(1f) }
            LaunchedEffect(Unit) {
                progressTarget = 0f
            }
            val remainingProgress by animateFloatAsState(
                targetValue = progressTarget,
                animationSpec = tween(durationMillis = 30000, easing = LinearEasing),
                label = "declineExpiryProgress"
            )

            val statusColors = LocalStatusColors.current
            val barColor by animateColorAsState(
                targetValue = when {
                    remainingProgress <= 0.13f -> MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
                    remainingProgress <= 0.27f -> statusColors.warning.copy(alpha = 0.22f)
                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                },
                animationSpec = tween(400),
                label = "declineExpiryColor"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clickable {
                            HapticManager.performClick(context)
                            onDecline()
                        }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = remainingProgress)
                                .fillMaxHeight()
                                .background(
                                    color = barColor,
                                    shape = CircleShape
                                )
                        )
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Decline",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        HapticManager.performSuccess(context)
                        onAccept()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = CircleShape
                ) {
                    Text(
                        text = "Accept",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }
    }
}
