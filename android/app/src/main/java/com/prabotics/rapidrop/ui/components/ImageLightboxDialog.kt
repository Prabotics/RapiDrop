package com.prabotics.rapidrop.ui.components

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prabotics.rapidrop.clipboard.ClipItem
import com.prabotics.rapidrop.clipboard.ImageMetadataHelper
import com.prabotics.rapidrop.clipboard.MediaStorageHelper
import com.prabotics.rapidrop.ui.HapticManager
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageLightboxDialog(
    clip: ClipItem,
    onDismiss: () -> Unit,
    onShare: (ClipItem) -> Unit,
    onSendToDevice: (ClipItem) -> Unit = {},
    onDelete: ((ClipItem) -> Unit)? = null,
) {
    val context = LocalContext.current
    val raw = clip.rawData ?: return
    val metadata = remember(raw) { ImageMetadataHelper.extractMetadata(raw) }

    val bitmap = remember(raw) {
        try {
            ImageMetadataHelper.decodeSampledBitmap(raw, 2048, 2048)
                ?: BitmapFactory.decodeByteArray(raw, 0, raw.size)
        } catch (_: OutOfMemoryError) {
            try {
                ImageMetadataHelper.decodeSampledBitmap(raw, 1024, 1024)
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    } ?: return

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(1800)
            isCopied = false
        }
    }

    val byteCount = raw.size.toLong()
    val formattedSize = remember(byteCount) {
        if (byteCount < 1024 * 1024) {
            String.format(java.util.Locale.US, "%.1f KB", byteCount / 1024.0)
        } else {
            String.format(java.util.Locale.US, "%.1f MB", byteCount / 1_048_576.0)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        BackHandler {
            onDismiss()
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp),
            ) {
                TopAppBar(
                    title = {
                        Column {
                            val headerTitle = if (!clip.fileName.isNullOrBlank()) clip.fileName else "Screenshot"
                            val timeStr = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(clip.timestamp))
                            Text(
                                text = headerTitle,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                            val dimText = if (metadata != null) "${metadata.width} × ${metadata.height} px, $formattedSize, $timeStr" else "$formattedSize, $timeStr"
                            Text(
                                text = dimText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                HapticManager.performClick(context)
                                onDismiss()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    actions = {
                        if (onDelete != null) {
                            IconButton(
                                onClick = {
                                    HapticManager.performClick(context)
                                    onDelete(clip)
                                    onDismiss()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete Image",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .padding(16.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1f) {
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                    } else {
                                        scale = 2.5f
                                    }
                                },
                            )
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                if (scale > 1f) {
                                    val maxOffsetX = (size.width * (scale - 1)) / 2
                                    val maxOffsetY = (size.height * (scale - 1)) / 2
                                    offsetX = (offsetX + pan.x).coerceIn(-maxOffsetX, maxOffsetX)
                                    offsetY = (offsetY + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                        shadowElevation = 2.dp,
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Full Preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offsetX,
                                    translationY = offsetY,
                                ),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            onClick = {
                                HapticManager.performSuccess(context)
                                MediaStorageHelper.writeMediaToClipboard(context, clip)
                                isCopied = true
                                Toast.makeText(context, "Image copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                        ) {
                            Icon(
                                imageVector = if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                contentDescription = "Copy Image",
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isCopied) "Copied" else "Copy",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                HapticManager.performClick(context)
                                onShare(clip)
                            },
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = "Share",
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Share",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }

                        Button(
                            onClick = {
                                HapticManager.performSuccess(context)
                                onSendToDevice(clip)
                            },
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1.1f)
                                .height(42.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Laptop,
                                contentDescription = "Send",
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Send",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                }
            }
        }
    }
}
