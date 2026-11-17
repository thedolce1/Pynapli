package com.pynapli.app.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliPill
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.FilterList
import com.pynapli.app.design.icons.filled.LocationOn

@Composable
internal fun QuickFilterRow(
    filters: GridFilters,
    onToggled: (QuickFilterId) -> Unit,
    onOpenSheet: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag(GridFilterTags.QUICK_ROW),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(
            key = "filters-button",
            contentType = "grid-filter-chip",
        ) {
            PynapliPill(
                text = if (filters.activeCount == 0) "Filters" else "Filters · ${filters.activeCount}",
                selected = filters.activeCount > 0,
                onClick = onOpenSheet,
                leading = {
                    Icon(
                        imageVector = Icons.Filled.FilterList,
                        contentDescription = null,
                        tint = PynapliColors.Accent,
                        modifier = Modifier.size(16.dp),
                    )
                },
                modifier = Modifier.testTag(GridFilterTags.OPEN_SHEET),
            )
        }
        item(
            key = QuickFilterId.Online.name,
            contentType = "grid-filter-chip",
        ) {
            PynapliPill(
                text = "Online",
                selected = filters.online,
                onClick = { onToggled(QuickFilterId.Online) },
            )
        }
        item(
            key = "nearby-radius",
            contentType = "grid-filter-chip",
        ) {
            val km = (filters.radiusMeters + 500) / 1_000
            PynapliPill(
                text = "≤ $km km",
                selected = false,
                onClick = onOpenSheet,
                leading = {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        tint = PynapliColors.Accent,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        item(
            key = QuickFilterId.Singles.name,
            contentType = "grid-filter-chip",
        ) {
            PynapliPill(
                text = "Singles",
                selected = filters.singles,
                onClick = { onToggled(QuickFilterId.Singles) },
            )
        }
        item(
            key = QuickFilterId.Linked.name,
            contentType = "grid-filter-chip",
        ) {
            PynapliPill(
                text = "Linked",
                selected = filters.linked,
                onClick = { onToggled(QuickFilterId.Linked) },
            )
        }
        item(
            key = QuickFilterId.Verified.name,
            contentType = "grid-filter-chip",
        ) {
            PynapliPill(
                text = "Verified",
                selected = filters.verified,
                onClick = { onToggled(QuickFilterId.Verified) },
            )
        }
        item(
            key = QuickFilterId.Tonight.name,
            contentType = "grid-filter-chip",
        ) {
            PynapliPill(
                text = "Tonight",
                selected = filters.tonightAvailable,
                onClick = { onToggled(QuickFilterId.Tonight) },
            )
        }
    }
}

internal object GridFilterTags {
    const val QUICK_ROW: String = "grid-quick-filter-row"
    const val OPEN_SHEET: String = "grid-filters-open"
    const val HEADER_OPEN_SHEET: String = "grid-header-filters-open"
    const val SHEET: String = "grid-filters-sheet"
    const val APPLY: String = "grid-filters-apply"
    const val CLEAR_ALL: String = "grid-filters-clear-all"
    const val OPEN_TONIGHT: String = "grid-filters-open-tonight"
}
