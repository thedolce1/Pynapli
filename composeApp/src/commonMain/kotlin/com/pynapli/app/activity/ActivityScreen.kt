package com.pynapli.app.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.pynapli.shared.distance.formatDistanceForUnit
import com.pynapli.shared.network.dto.ActivityEventDto
import com.pynapli.shared.settings.AppSettings
import kotlinx.coroutines.launch

@Composable
fun ActivityScreen(
    modifier: Modifier = Modifier,
    stateHolder: ActivityStateHolder = rememberActivityStateHolder(),
    distanceUnit: String = AppSettings.UNIT_METRIC,
    onOpenStore: () -> Unit = {}
) {
    val state by stateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(stateHolder) {
        stateHolder.load()
    }

    ActivityScreenContent(
        state = state,
        onTabSelected = { tab -> scope.launch { stateHolder.selectTab(tab) } },
        onAlbumRequestDecision = { eventId, approve ->
            scope.launch { stateHolder.respondToAlbumRequest(eventId, approve) }
        },
        onDismissEvent = { eventId -> scope.launch { stateHolder.dismissActivityEvent(eventId) } },
        onLoadMore = { scope.launch { stateHolder.loadMore() } },
        onRefresh = { scope.launch { stateHolder.load() } },
        onOpenStore = onOpenStore,
        distanceUnit = distanceUnit,
        modifier = modifier
    )
}

@Composable
private fun rememberActivityStateHolder(): ActivityStateHolder {
    return remember {
        ActivityStateHolder(
            repository = SampleActivityFeedRepository()
        )
    }
}

@Composable
private fun ActivityScreenContent(
    state: ActivityUiState,
    onTabSelected: (ActivityTabUi) -> Unit,
    onAlbumRequestDecision: (String, Boolean) -> Unit,
    onDismissEvent: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onOpenStore: () -> Unit,
    distanceUnit: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ActivityHeader(
            loading = state.isLoading,
            onRefresh = onRefresh
        )
        ActivityTabRow(
            selectedTab = state.selectedTab,
            onTabSelected = onTabSelected
        )
        ActivityInlineMessage(
            message = state.actionFeedback ?: state.errorMessage?.takeIf { state.items.isNotEmpty() },
            isError = state.actionFeedback == null && state.errorMessage != null
        )

        when {
            state.isLoading && state.items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            state.errorMessage != null && state.items.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            state.items.isEmpty() -> EmptyActivityState(
                label = "No activity yet.",
                modifier = Modifier.fillMaxSize()
            )

            state.visibleItems.isEmpty() -> EmptyActivityState(
                label = state.selectedTab.emptyLabel(),
                modifier = Modifier.fillMaxSize()
            )

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (state.gatedCount > 0) {
                        item(key = "activity-gate") {
                            ActivityGateRow(
                                gatedCount = state.gatedCount,
                                onOpenStore = onOpenStore
                            )
                        }
                    }
                    items(
                        items = state.visibleItems,
                        key = { item -> item.eventId }
                    ) { item ->
                        ActivityRow(
                            item = item,
                            pendingAction = state.pendingAlbumActionEventId == item.eventId,
                            pendingDismiss = state.pendingDismissEventId == item.eventId,
                            distanceUnit = distanceUnit,
                            onAlbumRequestDecision = onAlbumRequestDecision,
                            onDismissEvent = onDismissEvent
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    }
                    if (state.nextCursor != null) {
                        item(key = "activity-load-more") {
                            ActivityLoadMoreRow(
                                loading = state.isLoadingMore,
                                onLoadMore = onLoadMore
                            )
                        }
                    }
                    if (state.adSupported) {
                        item(key = "activity-ad-slot") {
                            ActivityAdSlot()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityGateRow(
    gatedCount: Int,
    onOpenStore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = "$gatedCount more hidden",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onOpenStore) {
            Text(text = "Select", maxLines = 1)
        }
    }
}

@Composable
private fun ActivityAdSlot() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Sponsored",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun ActivityInlineMessage(
    message: String?,
    isError: Boolean
) {
    if (message == null) return
    Text(
        text = message,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun ActivityTabRow(
    selectedTab: ActivityTabUi,
    onTabSelected: (ActivityTabUi) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ActivityTabUi.entries.forEach { tab ->
            item(key = tab.name) {
                AssistChip(
                    onClick = { onTabSelected(tab) },
                    label = {
                        Text(
                            text = tab.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (tab == selectedTab) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        labelColor = if (tab == selectedTab) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    ),
                    border = AssistChipDefaults.assistChipBorder(
                        enabled = true,
                        borderColor = MaterialTheme.colorScheme.outline
                    )
                )
            }
        }
    }
}

@Composable
private fun ActivityHeader(
    loading: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Activity",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onRefresh,
            enabled = !loading,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Refresh activity",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ActivityRow(
    item: ActivityEventDto,
    pendingAction: Boolean,
    pendingDismiss: Boolean,
    distanceUnit: String,
    onAlbumRequestDecision: (String, Boolean) -> Unit,
    onDismissEvent: (String) -> Unit
) {
    val distanceLabel = formatDistanceForUnit(
        distanceMeters = item.distanceMeters,
        fallbackLabel = item.distanceLabel,
        distanceUnit = distanceUnit
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ActivityAvatar(photoUrl = item.actorPhotoUrl)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = item.actorDisplayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = item.occurredAt.compactTimeLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = item.activityText(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                distanceLabel?.let { distance ->
                    Text(
                        text = distance,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
            AlbumRequestActions(
                item = item,
                pendingAction = pendingAction,
                onAlbumRequestDecision = onAlbumRequestDecision
            )
        }
        IconButton(
            onClick = { onDismissEvent(item.eventId) },
            enabled = !pendingDismiss,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
        ) {
            if (pendingDismiss) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss activity",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ActivityLoadMoreRow(
    loading: Boolean,
    onLoadMore: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        OutlinedButton(
            onClick = onLoadMore,
            enabled = !loading
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(16.dp)
                )
            }
            Text(text = if (loading) "Loading" else "Load more", maxLines = 1)
        }
    }
}

@Composable
private fun AlbumRequestActions(
    item: ActivityEventDto,
    pendingAction: Boolean,
    onAlbumRequestDecision: (String, Boolean) -> Unit
) {
    if (item.type != "album_request") return

    when (item.albumRequestStatus) {
        "pending" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onAlbumRequestDecision(item.eventId, false) },
                    enabled = !pendingAction,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = if (pendingAction) "..." else "Deny", maxLines = 1)
                }
                Button(
                    onClick = { onAlbumRequestDecision(item.eventId, true) },
                    enabled = !pendingAction,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = if (pendingAction) "..." else "Grant", maxLines = 1)
                }
            }
        }

        "approved",
        "denied" -> {
            Text(
                text = if (item.albumRequestStatus == "approved") "Access granted" else "Request denied",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ActivityAvatar(photoUrl: String?) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF2B2622), Color(0xFF6D5D4F))
                )
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (photoUrl != null) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun EmptyActivityState(
    label: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun ActivityEventDto.activityText(): String = when (type) {
    "profile_view" -> if (count > 1) "viewed you ${count}x" else "viewed you"
    "flirt" -> if (count > 1) "sent Sparks ${count}x" else "sent a Spark"
    "album_request" -> if (count > 1) "requested album access ${count}x" else "requested album access"
    "swipe_like" -> "liked you"
    "pineapple_like" -> "sent a Pineapple"
    "match" -> "matched with you"
    "partner_chat_invite" -> "sent a chat invite"
    else -> "interacted with you"
}

private fun String.compactTimeLabel(): String =
    if (length >= 16 && this[10] == 'T') substring(11, 16) else ""

private fun ActivityTabUi.emptyLabel(): String = when (this) {
    ActivityTabUi.All -> "No activity yet."
    ActivityTabUi.Views -> "No views yet."
    ActivityTabUi.Flirts -> "No flirts yet."
    ActivityTabUi.Likes -> "No likes yet."
    ActivityTabUi.Matches -> "No matches yet."
    ActivityTabUi.Albums -> "No album requests yet."
    ActivityTabUi.Invites -> "No invites yet."
}
