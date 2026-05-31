package com.pynapli.app.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

internal fun LazyGridScope.albumSection(
    kind: MediaAlbumKindUi,
    items: List<MediaAlbumItemUi>,
    isUploading: Boolean,
    onAdd: () -> Unit,
) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        AlbumSectionHeader(kind = kind)
    }

    item {
        AddMediaTile(
            kind = kind,
            isUploading = isUploading,
            onClick = onAdd,
        )
    }

    items(
        items = items,
        key = { item -> item.mediaAssetId },
    ) { item ->
        MediaTile(item = item)
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
private fun AlbumSectionHeader(kind: MediaAlbumKindUi) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 2.dp),
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
}

@Composable
private fun AddMediaTile(
    kind: MediaAlbumKindUi,
    isUploading: Boolean,
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
                .clickable(enabled = !isUploading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isUploading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(26.dp),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Add ${kind.title}",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = "Add",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun MediaTile(item: MediaAlbumItemUi) {
    val tone = item.mediaAssetId.tone()
    val mediaUrl = item.mediaUrl
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(tone)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
    ) {
        if (mediaUrl != null && item.mediaType == "image") {
            AsyncImage(
                model = mediaUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = if (item.processingStatus == "pending_upload") Icons.Filled.CloudUpload else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.86f),
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(28.dp),
            )
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
        Text(
            text = item.statusLabel,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.54f))
                    .padding(horizontal = 7.dp, vertical = 6.dp),
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
    val tones =
        listOf(
            Brush.linearGradient(listOf(Color(0xFF2A2622), Color(0xFFB08A44))),
            Brush.linearGradient(listOf(Color(0xFF17211E), Color(0xFF486C5D))),
            Brush.linearGradient(listOf(Color(0xFF251D22), Color(0xFF854D62))),
            Brush.linearGradient(listOf(Color(0xFF191F24), Color(0xFF53616D))),
            Brush.linearGradient(listOf(Color(0xFF20201E), Color(0xFF72634F))),
        )
    val index = fold(0) { total, char -> total + char.code }.mod(tones.size)
    return tones[index]
}
