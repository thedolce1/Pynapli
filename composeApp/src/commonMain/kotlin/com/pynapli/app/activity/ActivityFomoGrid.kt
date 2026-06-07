package com.pynapli.app.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.PhotoBottomScrim
import com.pynapli.app.design.PynapliColors
import com.pynapli.shared.distance.formatDistanceForUnit
import com.pynapli.shared.network.dto.ActivityEventDto

internal object ActivityFomoTags {
    const val GRID = "activity-fomo-grid"

    fun tile(eventId: String): String = "activity-fomo-tile-$eventId"

    const val LOCKED_TILE = "activity-fomo-locked-tile"
}

// A handful of frosted placeholder tiles is enough to read as "lots more waiting" without
// pretending we have the exact hidden faces (the server only returns a gated count, not the
// gated actors). Capped so the grid stays a teaser, not an endless wall of locks.
private const val MAX_LOCKED_TILES = 6

/**
 * Views and Flirts render as a 2-up profile grid (founder direction, 2026-06-07): mirroring the
 * Grid surface makes the revealed set feel larger and, for gated tiers, the frosted locked tiles
 * create FOMO toward Select. Likes (premium reveal list) and Requests (action rows) keep the list.
 */
@Composable
internal fun ActivityFomoGrid(
    items: List<ActivityEventDto>,
    gatedCount: Int,
    adSupported: Boolean,
    nextCursor: String?,
    isLoadingMore: Boolean,
    focusedEventId: String?,
    pendingDismissEventId: String?,
    distanceUnit: String,
    onTileClick: (ActivityEventDto) -> Unit,
    onDismissEvent: (String) -> Unit,
    onOpenStore: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val lockedTiles = gatedCount.coerceAtMost(MAX_LOCKED_TILES)

    LaunchedEffect(focusedEventId, items) {
        val eventId = focusedEventId ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.eventId == eventId }
        if (index >= 0) gridState.animateScrollToItem(index)
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize().testTag(ActivityFomoTags.GRID),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            count = items.size,
            key = { index -> items[index].eventId },
        ) { index ->
            val item = items[index]
            ActivityFomoTile(
                item = item,
                focused = focusedEventId == item.eventId,
                pendingDismiss = pendingDismissEventId == item.eventId,
                distanceUnit = distanceUnit,
                onTileClick = { onTileClick(item) },
                onDismiss = { onDismissEvent(item.eventId) },
            )
        }
        items(count = lockedTiles) {
            ActivityLockedTile(onClick = onOpenStore)
        }
        if (gatedCount > 0) {
            fullSpanItem(key = "fomo-unlock") {
                ActivityUnlockBanner(gatedCount = gatedCount, onOpenStore = onOpenStore)
            }
        }
        if (nextCursor != null) {
            fullSpanItem(key = "fomo-load-more") {
                ActivityLoadMoreRow(loading = isLoadingMore, onLoadMore = onLoadMore)
            }
        }
        if (adSupported) {
            fullSpanItem(key = "fomo-ad") { ActivityAdSlot() }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.fullSpanItem(
    key: String,
    content: @Composable () -> Unit,
) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun ActivityFomoTile(
    item: ActivityEventDto,
    focused: Boolean,
    pendingDismiss: Boolean,
    distanceUnit: String,
    onTileClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val distanceLabel =
        formatDistanceForUnit(
            distanceMeters = item.distanceMeters,
            fallbackLabel = item.distanceLabel,
            distanceUnit = distanceUnit,
        )
    val borderColor =
        if (focused) PynapliColors.Accent else PynapliColors.Border
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.82f)
                .clip(RoundedCornerShape(12.dp))
                .background(tileBackgroundBrush(item.actorUserId))
                .border(if (focused) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
                .clickable(onClick = onTileClick)
                .testTag(ActivityFomoTags.tile(item.eventId)),
    ) {
        item.actorPhotoUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        PhotoBottomScrim(heightFraction = 0.55f)

        if (item.isUnreadBadgeActivity()) {
            Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                ActivityNewPill()
            }
        }
        ActivityTileDismiss(
            pendingDismiss = pendingDismiss,
            onDismiss = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        )

        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.actorDisplayName,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.activityText(),
                color = Color(0xFFE6E1D8),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            distanceLabel?.let { distance ->
                Text(
                    text = distance,
                    color = Color(0xFFCFC9BF),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            if (item.source == PLAYING_TONIGHT_SOURCE) {
                Text(
                    text = "via Playing Tonight",
                    color = PynapliColors.Accent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp).testTag(VIA_PLAYING_TONIGHT_TEST_TAG),
                )
            }
        }
    }
}

@Composable
private fun ActivityTileDismiss(
    pendingDismiss: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(0x99000000))
                .clickable(enabled = !pendingDismiss, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        if (pendingDismiss) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Dismiss activity",
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun ActivityLockedTile(onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.82f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF1A1A20), Color(0xFF26262E)),
                    ),
                ).border(1.dp, PynapliColors.BorderSoft, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .testTag(ActivityFomoTags.LOCKED_TILE),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(PynapliColors.Accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = PynapliColors.Accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ActivityUnlockBanner(
    gatedCount: Int,
    onOpenStore: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PynapliColors.Accent.copy(alpha = 0.12f))
                .border(1.dp, PynapliColors.Accent.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenStore)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = PynapliColors.Accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "$gatedCount more waiting — see them all with Select",
            color = PynapliColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun tileBackgroundBrush(seed: String): Brush {
    val palettes =
        listOf(
            listOf(Color(0xFF2A2A2A), Color(0xFF7D5B3F)),
            listOf(Color(0xFF263238), Color(0xFF607D8B)),
            listOf(Color(0xFF1E1B1B), Color(0xFFB85C38)),
            listOf(Color(0xFF252525), Color(0xFF455A40)),
            listOf(Color(0xFF2B2622), Color(0xFF9A6B38)),
            listOf(Color(0xFF202124), Color(0xFF6B7280)),
        )
    val index = seed.fold(0) { total, char -> total + char.code }.mod(palettes.size)
    return Brush.linearGradient(palettes[index])
}
