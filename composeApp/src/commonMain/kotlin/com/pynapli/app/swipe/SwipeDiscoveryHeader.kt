package com.pynapli.app.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliPill

@Composable
internal fun SwipeHeader(
    activeFilterCount: Int,
    travelCityName: String?,
    onOpenFilters: () -> Unit,
    onOpenTravel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Style,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Swipe",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (travelCityName != null) "Travel: $travelCityName" else "Pass or like nearby profiles",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(
            onClick = onOpenTravel,
            modifier = Modifier.testTag(TravelModeTags.OPEN_SHEET),
        ) {
            Text(
                text = travelCityName ?: "Travel",
                maxLines = 1,
            )
        }
        TextButton(
            onClick = onOpenFilters,
            modifier = Modifier.testTag(SwipeFilterTags.OPEN_SHEET),
        ) {
            Text(
                text = if (activeFilterCount > 0) "Filters ($activeFilterCount)" else "Filters",
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun SwipeFilterRow(
    filters: List<SwipeFilterUi>,
    onFilterSelected: (SwipeFilterType) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        filters.forEach { filter ->
            item(key = filter.type.name) {
                PynapliPill(
                    text = filter.label,
                    selected = filter.active,
                    onClick = { onFilterSelected(filter.type) },
                    leading =
                        if (filter.locked) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = PynapliColors.Accent,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@Composable
internal fun SwipePaywallRow(
    message: String,
    onOpenStore: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenStore) {
            Text(text = "Social", maxLines = 1)
        }
    }
}
