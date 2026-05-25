package com.pynapli.app.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
internal fun GridHeader(
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
internal fun QuickFilterRow(
    filters: GridFilters,
    onToggled: (QuickFilterId) -> Unit,
    onOpenSheet: () -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag(GridFilterTags.QUICK_ROW),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "filters-button") {
            FiltersOpenChip(activeCount = filters.activeCount, onClick = onOpenSheet)
        }
        item(key = QuickFilterId.Online.name) {
            QuickToggleChip(
                label = "Online",
                active = filters.online,
                onClick = { onToggled(QuickFilterId.Online) }
            )
        }
        item(key = "nearby-radius") {
            NearbyRadiusChip(radiusMeters = filters.radiusMeters, onClick = onOpenSheet)
        }
        item(key = QuickFilterId.Singles.name) {
            QuickToggleChip(
                label = "Singles",
                active = filters.singles,
                onClick = { onToggled(QuickFilterId.Singles) }
            )
        }
        item(key = QuickFilterId.Linked.name) {
            QuickToggleChip(
                label = "Linked",
                active = filters.linked,
                onClick = { onToggled(QuickFilterId.Linked) }
            )
        }
        item(key = QuickFilterId.Verified.name) {
            QuickToggleChip(
                label = "Verified",
                active = filters.verified,
                onClick = { onToggled(QuickFilterId.Verified) }
            )
        }
        item(key = QuickFilterId.Tonight.name) {
            QuickToggleChip(
                label = "Tonight",
                active = filters.tonightAvailable,
                onClick = { onToggled(QuickFilterId.Tonight) }
            )
        }
    }
}

@Composable
private fun FiltersOpenChip(activeCount: Int, onClick: () -> Unit) {
    val label = if (activeCount == 0) "Filters" else "Filters · $activeCount"
    AssistChip(
        onClick = onClick,
        label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.FilterList,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = MaterialTheme.colorScheme.primary
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier.testTag(GridFilterTags.OPEN_SHEET)
    )
}

@Composable
private fun QuickToggleChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    AssistChip(
        onClick = onClick,
        label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            labelColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = MaterialTheme.colorScheme.outline
        )
    )
}

@Composable
private fun NearbyRadiusChip(radiusMeters: Int, onClick: () -> Unit) {
    val km = (radiusMeters + 500) / 1_000
    val label = "≤ ${km} km"
    AssistChip(
        onClick = onClick,
        label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = MaterialTheme.colorScheme.primary
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = MaterialTheme.colorScheme.outline
        )
    )
}

internal object GridFilterTags {
    const val QUICK_ROW: String = "grid-quick-filter-row"
    const val OPEN_SHEET: String = "grid-filters-open"
    const val SHEET: String = "grid-filters-sheet"
    const val APPLY: String = "grid-filters-apply"
    const val CLEAR_ALL: String = "grid-filters-clear-all"
}

@Composable
internal fun GridPaywallRow(
    message: String,
    onOpenStore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onOpenStore) {
            Text(text = "Social", maxLines = 1)
        }
    }
}

@Composable
internal fun GridAdSlot() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Sponsored",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
internal fun OnlineDot(
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
