package com.pynapli.app.grid

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.conversation.SampleConversationRepository
import com.pynapli.app.location.LocationUiState
import com.pynapli.app.profile.ProfileDetailSheet
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profile.SamplePublicProfileRepository
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun GridScreen(
    modifier: Modifier = Modifier,
    stateHolder: GridStateHolder = rememberGridStateHolder(),
    profileDetailStateHolder: ProfileDetailStateHolder = rememberProfileDetailStateHolder(),
    conversationStateHolder: ConversationStateHolder = rememberConversationStateHolder(),
    viewerUserId: String? = null,
    locationState: LocationUiState = LocationUiState(),
    profileImageUrl: String? = null,
    onUseCurrentLocation: () -> Unit = {},
    onProfileIconClick: () -> Unit = {}
) {
    val state by stateHolder.state.collectAsState()
    val profileState by profileDetailStateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(stateHolder) {
        stateHolder.load()
    }

    GridScreenContent(
        state = state,
        locationState = locationState,
        profileImageUrl = profileImageUrl,
        onFilterSelected = stateHolder::selectFilter,
        onProfileSelected = { card ->
            scope.launch { profileDetailStateHolder.open(card) }
        },
        onUseCurrentLocation = onUseCurrentLocation,
        onProfileIconClick = onProfileIconClick,
        modifier = modifier
    )

    LaunchedEffect(profileState.blockedTargetUserId) {
        val blockedId = profileDetailStateHolder.consumeBlockedTargetUserId() ?: return@LaunchedEffect
        stateHolder.removeCardById(blockedId)
        profileDetailStateHolder.dismiss()
    }

    if (profileState.visible) {
        ProfileDetailSheet(
            state = profileState,
            onDismiss = profileDetailStateHolder::dismiss,
            onSpark = {
                scope.launch { profileDetailStateHolder.sendSpark() }
            },
            onRequestAlbum = {
                scope.launch { profileDetailStateHolder.requestAlbumAccess() }
            },
            onViewPrivateAlbum = {
                scope.launch { profileDetailStateHolder.loadPrivateAlbum() }
            },
            onMessage = {
                val card = profileState.selectedCard
                if (card != null) {
                    profileDetailStateHolder.dismiss()
                    scope.launch {
                        conversationStateHolder.openDirect(
                            card = card,
                            viewerUserId = viewerUserId
                        )
                    }
                }
            },
            onBlock = { reason ->
                scope.launch { profileDetailStateHolder.blockUser(reason) }
            },
            onReport = { category, note ->
                scope.launch { profileDetailStateHolder.submitReport(category, note) }
            }
        )
    }

}

@Composable
private fun rememberGridStateHolder(): GridStateHolder {
    return remember {
        GridStateHolder(
            repository = SampleGridFeedRepository()
        )
    }
}

@Composable
private fun rememberProfileDetailStateHolder(): ProfileDetailStateHolder {
    return remember {
        ProfileDetailStateHolder(
            repository = SamplePublicProfileRepository()
        )
    }
}

@Composable
private fun rememberConversationStateHolder(): ConversationStateHolder {
    return remember {
        ConversationStateHolder(
            repository = SampleConversationRepository()
        )
    }
}

@Composable
private fun GridScreenContent(
    state: GridUiState,
    locationState: LocationUiState,
    profileImageUrl: String?,
    onFilterSelected: (GridFilterType) -> Unit,
    onProfileSelected: (GridProfileCardUi) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onProfileIconClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        GridHeader(
            locationLabel = state.locationLabel,
            profileImageUrl = profileImageUrl,
            onProfileIconClick = onProfileIconClick
        )
        FilterRow(
            filters = state.filters,
            onFilterSelected = onFilterSelected
        )
        GridBody(
            state = state,
            locationState = locationState,
            onProfileSelected = onProfileSelected,
            onUseCurrentLocation = onUseCurrentLocation,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun GridHeader(
    locationLabel: String,
    profileImageUrl: String?,
    onProfileIconClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF4E342E), Color(0xFFC39A49))
                    )
                )
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable(onClick = onProfileIconClick),
            contentAlignment = Alignment.Center
        ) {
            if (profileImageUrl != null) {
                AsyncImage(
                    model = profileImageUrl,
                    contentDescription = "Profile",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = "Profile",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
            OnlineDot(
                modifier = Modifier.align(Alignment.BottomEnd),
                size = 10
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = locationLabel,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = {},
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(
                imageVector = Icons.Filled.FilterList,
                contentDescription = "Filters",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun FilterRow(
    filters: List<GridFilterUi>,
    onFilterSelected: (GridFilterType) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filters.forEach { filter ->
            item(key = filter.type.name) {
                FilterChip(
                    label = filter.label,
                    active = filter.active,
                    icon = if (filter.type == GridFilterType.Tonight) Icons.Filled.StarBorder else null,
                    onClick = { onFilterSelected(filter.type) }
                )
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    active: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            labelColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = MaterialTheme.colorScheme.outline
        )
    )
}

@Composable
private fun ProfileGrid(
    cards: List<GridProfileCardUi>,
    onProfileSelected: (GridProfileCardUi) -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 1.dp, end = 1.dp, bottom = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        items(
            items = cards,
            key = { card -> card.id }
        ) { card ->
            GridProfileTile(
                card = card,
                onClick = { onProfileSelected(card) }
            )
        }
    }
}

@Composable
private fun GridProfileTile(
    card: GridProfileCardUi,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.78f)
            .clickable(onClick = onClick)
            .background(card.tone.backgroundBrush())
    ) {
        card.thumbnailUrl?.let { thumbnailUrl ->
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.52f to Color.Transparent,
                            1.0f to Color(0xD9000000)
                        )
                    )
                )
        )

        if (card.linkedPartnerTone != null) {
            LinkedPartnerBadge(
                color = card.linkedPartnerTone.badgeColor(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (card.online) {
                OnlineDot(size = 8)
            }

            Text(
                text = card.displayName,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            card.distanceLabel?.let { distance ->
                Text(
                    text = distance,
                    color = Color(0xFFD7D7D7),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

@Composable
private fun OnlineDot(
    modifier: Modifier = Modifier,
    size: Int
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(0xFF13E58A))
            .border(1.dp, Color(0xFF050505), CircleShape)
    )
}

@Composable
private fun GridBody(
    state: GridUiState,
    locationState: LocationUiState,
    onProfileSelected: (GridProfileCardUi) -> Unit,
    onUseCurrentLocation: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        state.isRefreshing && state.cards.isEmpty() -> {
            Box(
                modifier = modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        state.errorCode == "LOCATION_REQUIRED" && state.cards.isEmpty() -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Use current location to load nearby profiles.",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onUseCurrentLocation,
                    enabled = !locationState.isUpdating,
                    modifier = Modifier.padding(top = 14.dp)
                ) {
                    Text(text = if (locationState.isUpdating) "Updating..." else "Use current location")
                }
                locationState.errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        }

        state.errorMessage != null && state.cards.isEmpty() -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = state.errorMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }

        else -> {
            ProfileGrid(
                cards = state.cards,
                onProfileSelected = onProfileSelected,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun LinkedPartnerBadge(
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color(0xB3000000), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = "Linked partner",
            tint = Color.White,
            modifier = Modifier.size(15.dp)
        )
    }
}

private fun GridCardTone.backgroundBrush(): Brush = when (this) {
    GridCardTone.Warm -> Brush.linearGradient(listOf(Color(0xFF2A2A2A), Color(0xFF7D5B3F)))
    GridCardTone.Slate -> Brush.linearGradient(listOf(Color(0xFF263238), Color(0xFF607D8B)))
    GridCardTone.Rust -> Brush.linearGradient(listOf(Color(0xFF1E1B1B), Color(0xFFB85C38)))
    GridCardTone.DeepGreen -> Brush.linearGradient(listOf(Color(0xFF252525), Color(0xFF455A40)))
    GridCardTone.Bronze -> Brush.linearGradient(listOf(Color(0xFF2B2622), Color(0xFF9A6B38)))
    GridCardTone.Graphite -> Brush.linearGradient(listOf(Color(0xFF202124), Color(0xFF6B7280)))
    GridCardTone.Teal -> Brush.linearGradient(listOf(Color(0xFF171717), Color(0xFF355C5B)))
    GridCardTone.Rose -> Brush.linearGradient(listOf(Color(0xFF2E2524), Color(0xFF884C5E)))
    GridCardTone.Cocoa -> Brush.linearGradient(listOf(Color(0xFF1C1B1A), Color(0xFF6D5D4F)))
    GridCardTone.Moss -> Brush.linearGradient(listOf(Color(0xFF1F2421), Color(0xFF526E48)))
    GridCardTone.Stone -> Brush.linearGradient(listOf(Color(0xFF212121), Color(0xFF7B6257)))
    GridCardTone.Midnight -> Brush.linearGradient(listOf(Color(0xFF1A202C), Color(0xFF57606A)))
}

private fun LinkedPartnerTone.badgeColor(): Color = when (this) {
    LinkedPartnerTone.Violet -> Color(0xFF8B5CF6)
    LinkedPartnerTone.Gold -> Color(0xFFC39A49)
    LinkedPartnerTone.Pink -> Color(0xFFE85D75)
    LinkedPartnerTone.Teal -> Color(0xFF4DB6AC)
    LinkedPartnerTone.Sun -> Color(0xFFFFC928)
}
