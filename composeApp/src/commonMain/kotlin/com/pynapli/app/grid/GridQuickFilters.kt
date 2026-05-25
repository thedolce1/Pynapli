package com.pynapli.app.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
