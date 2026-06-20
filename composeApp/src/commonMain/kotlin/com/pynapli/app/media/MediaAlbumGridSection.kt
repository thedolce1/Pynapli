package com.pynapli.app.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.PynapliPlaceholderGradients

internal fun LazyGridScope.albumSection(
    kind: MediaAlbumKindUi,
    items: List<MediaAlbumItemUi>,
    isUploading: Boolean,
    canAdd: Boolean,
    capCountLabel: String,
    busyAssetIds: Set<String>,
    canRetryUpload: Boolean,
    reorderState: AlbumReorderState,
    onAdd: () -> Unit,
    onRetryUpload: () -> Unit,
    onRequestDelete: (MediaAlbumItemUi) -> Unit,
    onSetAsMain: (MediaAlbumItemUi) -> Unit,
) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        AlbumSectionHeader(kind = kind, reorderable = items.size > 1)
    }

    if (canRetryUpload && !isUploading) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            RetryUploadRow(kind = kind, onRetry = onRetryUpload)
        }
    }

    item(key = "album-add:${kind.apiValue}") {
        AddMediaTile(
            kind = kind,
            isUploading = isUploading,
            canAdd = canAdd,
            capCountLabel = capCountLabel,
            onClick = onAdd,
        )
    }

    itemsIndexed(
        items = items,
        key = { _, item -> albumPhotoKey(kind, item.mediaAssetId) },
    ) { index, item ->
        val key = albumPhotoKey(kind, item.mediaAssetId)
        val isPrimary = kind == MediaAlbumKindUi.Profile && index == 0
        val isDragging = reorderState.draggingKey == key
        MediaTile(
            item = item,
            isPrimary = isPrimary,
            isBusy = item.mediaAssetId in busyAssetIds,
            onDelete = { onRequestDelete(item) },
            onSetAsMain = if (kind == MediaAlbumKindUi.Profile && !isPrimary) ({ onSetAsMain(item) }) else null,
            modifier =
                Modifier
                    .then(if (isDragging) Modifier else Modifier.animateItem())
                    .albumDraggedTile(reorderState, key),
        )
    }

    if (items.isEmpty() && !isUploading) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyAlbumRow(kind = kind)
        }
    }
}

@Composable
internal fun LoadingAlbumRow() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Loading albums",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AlbumSectionHeader(
    kind: MediaAlbumKindUi,
    reorderable: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (kind == MediaAlbumKindUi.Private) Icons.Filled.Lock else Icons.Filled.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = kind.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (reorderable) {
            Text(
                text =
                    if (kind == MediaAlbumKindUi.Profile) {
                        "Hold a photo to reorder · tap ★ to make it your main"
                    } else {
                        "Hold a photo to reorder"
                    },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun RetryUploadRow(
    kind: MediaAlbumKindUi,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Last upload didn't finish.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text(text = "Retry ${kind.title.lowercase()} upload")
        }
    }
}

@Composable
private fun AddMediaTile(
    kind: MediaAlbumKindUi,
    isUploading: Boolean,
    canAdd: Boolean,
    capCountLabel: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .clickable(enabled = !isUploading && canAdd, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isUploading ->
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(26.dp),
                )

            !canAdd ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = capCountLabel,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = "Album full",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

            else ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add ${kind.title}",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        text = "Add",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = capCountLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
        }
    }
}

@Composable
private fun MediaTile(
    item: MediaAlbumItemUi,
    isPrimary: Boolean,
    isBusy: Boolean,
    onDelete: () -> Unit,
    onSetAsMain: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tone = item.mediaAssetId.tone()
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(tone)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
    ) {
        if (item.isOwnerVisibleImage) {
            AsyncImage(
                model = item.mediaUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = if (item.isUploadFailed) Icons.Filled.CloudUpload else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.86f),
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(28.dp),
            )
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            if (isPrimary) {
                Text(
                    text = "Main",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            item.statusBadgeLabel?.let { badge ->
                Text(
                    text = badge,
                    color = if (item.isUploadFailed || item.moderationStatus == "removed") MaterialTheme.colorScheme.error else Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }

        if (item.albumKind == MediaAlbumKindUi.Private) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.54f))
                        .padding(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Private",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        if (isBusy) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onSetAsMain != null) {
                    TileActionButton(
                        icon = Icons.Filled.Star,
                        contentDescription = "Set as main photo",
                        onClick = onSetAsMain,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TileActionButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = "Delete photo",
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun TileActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .padding(2.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun EmptyAlbumRow(kind: MediaAlbumKindUi) {
    Text(
        text = if (kind == MediaAlbumKindUi.Private) "No private media." else "No profile media.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
    )
}

private fun String.tone(): Brush {
    val tones = PynapliPlaceholderGradients.swipeDeckTones
    return tones[fold(0) { total, char -> total + char.code }.mod(tones.size)]
}
