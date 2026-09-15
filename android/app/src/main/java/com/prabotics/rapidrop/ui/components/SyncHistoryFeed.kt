package com.prabotics.rapidrop.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prabotics.rapidrop.clipboard.ClipContentType
import com.prabotics.rapidrop.clipboard.ClipItem
import com.prabotics.rapidrop.clipboard.ImageMetadataHelper
import com.prabotics.rapidrop.ui.HapticManager



enum class HistoryCategoryFilter(val label: String) {
    ALL("All"),
    TEXT("Text"),
    LINKS("Links"),
    MEDIA("Media")
}

@Composable
fun SyncHistoryFeed(
    recentClips: List<ClipItem>,
    pinnedClipIds: Set<String> = emptySet(),
    showSyncHistory: Boolean = true,
    onCopyClip: (ClipItem) -> Unit,
    onShareClip: (ClipItem) -> Unit,
    onClearHistory: () -> Unit,
    onDeleteClip: (ClipItem) -> Unit = {},
    onSendClip: (ClipItem) -> Unit = {},
    onTogglePinClip: (ClipItem) -> Unit = {},
    onToggleShowSyncHistory: () -> Unit = {},
    onInspectClip: ((ClipItem) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activeInspectorClip by remember { mutableStateOf<ClipItem?>(null) }
    var showClearConfirmationDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = activeInspectorClip != null) {
        activeInspectorClip = null
    }

    if (showClearConfirmationDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearConfirmationDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Clear all history?",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                )
            },
            text = {
                Text(
                    text = "All synchronized clips and file records will be permanently removed from this device.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        HapticManager.performSuccess(context)
                        onClearHistory()
                        showClearConfirmationDialog = false
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirmationDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(HistoryCategoryFilter.ALL) }

    val sortedAndFilteredClips = remember(recentClips, pinnedClipIds, searchQuery, selectedFilter) {
        val filtered = recentClips.filter { clip ->
            val matchesCategory = when (selectedFilter) {
                HistoryCategoryFilter.ALL -> true
                HistoryCategoryFilter.TEXT -> clip.type == ClipContentType.TEXT
                HistoryCategoryFilter.LINKS -> clip.type == ClipContentType.URL
                HistoryCategoryFilter.MEDIA -> clip.type == ClipContentType.IMAGE || clip.type == ClipContentType.FILE
            }
            val matchesQuery = searchQuery.isBlank() ||
                clip.displayName.contains(searchQuery, ignoreCase = true) ||
                (clip.textContent?.contains(searchQuery, ignoreCase = true) == true)
            matchesCategory && matchesQuery
        }
        val (pinned, unpinned) = filtered.partition { it.id.toString() in pinnedClipIds }
        pinned + unpinned
    }

    if (onInspectClip == null) {
        activeInspectorClip?.let { clip ->
        when (clip.type) {
            ClipContentType.IMAGE -> {
                ImageLightboxDialog(
                    clip = clip,
                    onDismiss = { activeInspectorClip = null },
                    onShare = onShareClip,
                    onSendToDevice = onSendClip,
                    onDelete = onDeleteClip
                )
            }
            ClipContentType.TEXT, ClipContentType.URL -> {
                TextInspectorDialog(
                    clip = clip,
                    onDismiss = { activeInspectorClip = null },
                    onCopy = onCopyClip,
                    onShare = onShareClip,
                    onSendToDevice = onSendClip,
                    onDelete = onDeleteClip
                )
            }
            ClipContentType.FILE -> {
                FileInspectorDialog(
                    clip = clip,
                    onDismiss = { activeInspectorClip = null },
                    onShare = onShareClip,
                    onSendToDevice = onSendClip,
                    onDelete = onDeleteClip
                )
            }
        }
    }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (showSyncHistory && recentClips.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${recentClips.size} items",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        HapticManager.performClick(context)
                        showClearConfirmationDialog = true
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "Clear All",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        }
        if (showSyncHistory && recentClips.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = "Search clips...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HistoryCategoryFilter.entries.forEach { filter ->
                    val isSelected = selectedFilter == filter
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            HapticManager.performClick(context)
                            selectedFilter = filter
                        },
                        label = {
                            Text(
                                text = filter.label,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                )
                            )
                        },
                        shape = CircleShape,
                        border = null,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        }

        if (!showSyncHistory) {
            PrivacyModeActiveCard(onEnableHistory = onToggleShowSyncHistory)
        } else {
            AnimatedContent(
                targetState = recentClips.isEmpty(),
                transitionSpec = {
                    fadeIn(spring(stiffness = 500f, dampingRatio = 0.8f)) togetherWith
                        fadeOut(spring(stiffness = 500f, dampingRatio = 0.8f))
                },
                label = "emptyOrHistoryTransition"
            ) { isEmpty ->
                if (isEmpty) {
                    EmptyHistoryCard()
                } else if (sortedAndFilteredClips.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No matching clips",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Try searching for a different term or clear the filter.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            FilledTonalButton(
                                onClick = {
                                    HapticManager.performClick(context)
                                    searchQuery = ""
                                    selectedFilter = HistoryCategoryFilter.ALL
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Clear Filter", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(spring(stiffness = 600f, dampingRatio = 0.8f)),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        sortedAndFilteredClips.forEach { clip ->
                            key(clip.id) {
                                SyncHistoryItemRow(
                                    clip = clip,
                                    isPinned = clip.id.toString() in pinnedClipIds,
                                    onCopy = { onCopyClip(clip) },
                                    onShare = { onShareClip(clip) },
                                    onDelete = { onDeleteClip(clip) },
                                    onTogglePin = { onTogglePinClip(clip) },
                                    onItemClick = {
                                        if (onInspectClip != null) {
                                            onInspectClip(clip)
                                        } else {
                                            activeInspectorClip = clip
                                        }
                                    }
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
private fun EmptyHistoryCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.ContentPaste,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Text(
            text = "No clipboard history yet",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Items you copy or files you receive will appear here automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PrivacyModeActiveCard(onEnableHistory: () -> Unit) {
    val context = LocalContext.current
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Text(
                text = "Sync History is Off",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )

            Text(
                text = "Clipboard items are synced in-memory only and not saved to disk.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp, start = 8.dp, end = 8.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            FilledTonalButton(
                onClick = {
                    HapticManager.performClick(context)
                    onEnableHistory()
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text("Turn on history", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SyncHistoryItemRow(
    clip: ClipItem,
    isPinned: Boolean = false,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onItemClick: () -> Unit
) {
    val context = LocalContext.current
    var isCopied by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val imageMetadata = remember(clip.id) {
        if (clip.type == ClipContentType.IMAGE) {
            ImageMetadataHelper.extractMetadata(clip.rawData)
        } else null
    }

    var bitmap by remember(clip.id) {
        mutableStateOf<android.graphics.Bitmap?>(ImageMetadataHelper.getCachedThumbnail(clip.id))
    }

    LaunchedEffect(clip.id, clip.rawData) {
        if (bitmap == null && clip.type == ClipContentType.IMAGE && clip.rawData != null && clip.rawData.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val decoded = ImageMetadataHelper.decodeSampledBitmap(clip.id, clip.rawData, 120, 120)
                if (decoded != null) {
                    bitmap = decoded
                }
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                HapticManager.performClick(context)
                onItemClick()
            }
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        color = if (isPinned) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (clip.type) {
                ClipContentType.IMAGE -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                        modifier = Modifier.size(44.dp)
                    ) {
                        val currentBitmap = bitmap
                        if (currentBitmap != null) {
                            Image(
                                bitmap = currentBitmap.asImageBitmap(),
                                contentDescription = "Image Thumbnail",
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
                ClipContentType.TEXT -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Subject,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                ClipContentType.URL -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                ClipContentType.FILE -> {
                    val ext = clip.fileName?.substringAfterLast('.', "")?.uppercase() ?: "FILE"
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                            if (ext.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(3.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(2.dp)
                                ) {
                                    Text(
                                        text = ext.take(4),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 8.sp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        ),
                                        modifier = Modifier.padding(horizontal = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val titleText = when (clip.type) {
                ClipContentType.IMAGE, ClipContentType.FILE -> clip.displayName
                ClipContentType.TEXT, ClipContentType.URL -> clip.textContent?.replace("\n", " ")?.trim() ?: ""
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val relativeTime = remember(clip.timestamp) { getRelativeTimeSpan(clip.timestamp) }
                val metadataLabel = when (clip.type) {
                    ClipContentType.IMAGE -> {
                        val sizeStr = imageMetadata?.formattedSize ?: "${(clip.rawData?.size ?: 0) / 1024} KB"
                        if (imageMetadata != null && imageMetadata.resolutionLabel.isNotEmpty()) {
                            "${imageMetadata.format}, ${imageMetadata.resolutionLabel}, $sizeStr"
                        } else {
                            sizeStr
                        }
                    }
                    ClipContentType.TEXT -> {
                        "${clip.textContent?.length ?: 0} chars"
                    }
                    ClipContentType.URL -> {
                        try {
                            java.net.URI(clip.textContent ?: "").host ?: "web link"
                        } catch (_: Exception) {
                            "web link"
                        }
                    }
                    ClipContentType.FILE -> {
                        "${(clip.rawData?.size ?: 0) / 1024} KB"
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isPinned) {
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Text(
                        text = metadataLabel,
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = relativeTime,
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)),
                        maxLines = 1
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        HapticManager.performSuccess(context)
                        isCopied = true
                        onCopy()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    AnimatedContent(
                        targetState = isCopied,
                        transitionSpec = {
                            (fadeIn(spring(stiffness = 500f, dampingRatio = 0.7f)) + scaleIn(spring(stiffness = 500f, dampingRatio = 0.6f)))
                                .togetherWith(fadeOut(tween(100)))
                        },
                        label = "copyIconMorph"
                    ) { copied ->
                        Icon(
                            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = "Copy",
                            tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = {
                            HapticManager.performClick(context)
                            showMenu = true
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "More actions",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (isPinned) "Unpin clip" else "Pin to top",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onTogglePin()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.PushPin,
                                    contentDescription = null,
                                    tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Share",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onShare()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Delete",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                )
                            },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

internal fun getRelativeTimeSpan(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0L || timestamp >= now) return "Just now"
    val diff = now - timestamp
    val seconds = diff / 1000L
    val minutes = seconds / 60L
    val hours = minutes / 60L
    val days = hours / 24L
    val weeks = days / 7L

    return when {
        seconds < 60L -> "Just now"
        minutes < 60L -> "${minutes}m ago"
        hours < 24L -> "${hours}h ago"
        days < 7L -> "${days}d ago"
        else -> "${weeks}w ago"
    }
}
