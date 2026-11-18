package com.pynapli.app.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliPlaceholderGradients
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Close
import com.pynapli.app.design.icons.filled.Lock
import com.pynapli.app.design.pynapliIconButtonSize
import com.pynapli.app.grid.GridProfileTile
import com.pynapli.app.grid.GridWidePaneMinWidth
import com.pynapli.app.grid.gridWideTileMinSize
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
 * Views and Flirts (Likes You) render as a Grid of the Grid's own cards (founder direction,
 * 2026-06-07; Grid card since round 5): mirroring the Grid makes the revealed set feel larger and,
 * for gated tiers, the frosted locked tiles create FOMO toward Select. Gating is the server's
 * Activity reveal rule, unchanged. Requests (action rows) keeps the list.
 */
@Composable
internal fun ActivityFomoGrid(
    items: List<ActivityEventDto>,
    gatedCount: Int,
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

    // Same column rule as the discovery grid (3-up on phone-width panes, adaptive at the Grid's
    // tile size on wider ones), so Likes You and Views read as a Grid of people.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val columns =
            if (maxWidth < GridWidePaneMinWidth) {
                GridCells.Fixed(3)
            } else {
                GridCells.Adaptive(minSize = gridWideTileMinSize(LocalDensity.current.fontScale))
            }
        LazyVerticalGrid(
            state = gridState,
            columns = columns,
            modifier = Modifier.fillMaxSize().testTag(ActivityFomoTags.GRID),
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
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
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.fullSpanItem(
    key: String,
    content: @Composable () -> Unit,
) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/**
 * The Grid's own card (photo, name, online dot, distance; GRID_IMPLEMENTATION_SPEC "Grid Card
 * Design") so Likes You reads as a Grid of people, with the Activity context (what they did, a
 * Pineapple Like note, "via Playing Tonight") as a caption under the photo rather than on it. The
 * NEW pill and dismiss button sit on corners the Grid card leaves free for Activity actors.
 */
@Composable
private fun ActivityFomoTile(
    item: ActivityEventDto,
    focused: Boolean,
    pendingDismiss: Boolean,
    distanceUnit: String,
    onTileClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val card = remember(item) { item.toActivityProfileCardUi() }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box {
            GridProfileTile(
                card = card,
                distanceUnit = distanceUnit,
                onClick = onTileClick,
                modifier =
                    Modifier
                        .then(
                            if (focused) Modifier.border(2.dp, PynapliColors.Accent, PynapliShape.SmallShape) else Modifier,
                        ).testTag(ActivityFomoTags.tile(item.eventId)),
            )
            if (item.isUnreadBadgeActivity()) {
                Box(modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) {
                    ActivityNewPill()
                }
            }
            ActivityTileDismiss(
                pendingDismiss = pendingDismiss,
                onDismiss = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = if (item.pairDisplayName != null) "${item.displayNames()} ${item.activityText()}" else item.activityText(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.noteText?.let { note ->
                Text(
                    text = "\u201C$note\u201D",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall,
                    fontStyle = FontStyle.Italic,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(ACTIVITY_LIKE_NOTE_TEST_TAG),
                )
            }
            if (item.source == PLAYING_TONIGHT_SOURCE) {
                Text(
                    text = "via Playing Tonight",
                    color = PynapliColors.Accent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.testTag(VIA_PLAYING_TONIGHT_TEST_TAG),
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
                // Compact 28dp visual on phones (40dp on tablets and computers), >=48dp touch target.
                .minimumInteractiveComponentSize()
                .size(pynapliIconButtonSize(28.dp))
                .clip(CircleShape)
                .background(PynapliColors.OverlayScrim)
                .handCursor(enabled = !pendingDismiss)
                .clickable(enabled = !pendingDismiss, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        if (pendingDismiss) {
            CircularProgressIndicator(
                color = PynapliColors.OnPhotoPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Dismiss activity",
                tint = PynapliColors.OnPhotoPrimary,
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
                .aspectRatio(1f)
                .clip(PynapliShape.SmallShape)
                .background(PynapliPlaceholderGradients.neutralTile)
                .border(1.dp, PynapliColors.BorderSoft, PynapliShape.SmallShape)
                .handCursor()
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
                .clip(PynapliShape.CardShape)
                .background(PynapliColors.Accent.copy(alpha = 0.12f))
                .border(1.dp, PynapliColors.Accent.copy(alpha = 0.40f), PynapliShape.CardShape)
                .handCursor()
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
