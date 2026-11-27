package com.pynapli.app.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliPill
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Check
import com.pynapli.app.design.icons.filled.FilterList
import com.pynapli.app.design.icons.filled.Star
import com.pynapli.app.design.icons.filled.StarBorder

/**
 * The Grid's header chip row (plan G8): Filters (with its active count), then Online · Favorites ·
 * Fresh · Tonight · Verified · No single men. It scrolls sideways, and an active chip carries a check as well as the
 * accent outline, so "on" reads at a glance rather than by a faint tint.
 *
 * Singles and Linked moved out of the row into the filter sheet's Who section (where they already
 * were): the row holds the one-tap answers to "who is around right now", and five of those fit a
 * phone without scrolling past the fold. The radius chip went too: distance lives in the sheet.
 *
 * [showingFavorites] null hides the Favorites chip (no Favorites holder wired). Favorites is a
 * view, not a filter: tapping it switches the Grid to Favorites and back.
 */
@Composable
internal fun QuickFilterRow(
    filters: GridFilters,
    onToggled: (QuickFilterId) -> Unit,
    onOpenSheet: () -> Unit,
    showingFavorites: Boolean? = null,
    onToggleFavorites: () -> Unit = {},
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
                leading = { ChipIcon(Icons.Filled.FilterList) },
                modifier = Modifier.testTag(GridFilterTags.OPEN_SHEET),
            )
        }
        item(key = QuickFilterId.Online.name, contentType = "grid-filter-chip") {
            QuickChip(label = "Online", active = filters.online, onClick = { onToggled(QuickFilterId.Online) })
        }
        if (showingFavorites != null) {
            item(key = "favorites-view", contentType = "grid-filter-chip") {
                PynapliPill(
                    text = "Favorites",
                    selected = showingFavorites,
                    onClick = onToggleFavorites,
                    leading = { ChipIcon(if (showingFavorites) Icons.Filled.Star else Icons.Filled.StarBorder) },
                    modifier = Modifier.testTag(GridFavoritesTags.VIEW_FAVORITES),
                )
            }
        }
        item(key = QuickFilterId.Fresh.name, contentType = "grid-filter-chip") {
            QuickChip(
                label = "Fresh",
                active = filters.fresh,
                onClick = { onToggled(QuickFilterId.Fresh) },
                modifier = Modifier.testTag(GridFilterTags.FRESH_CHIP),
            )
        }
        item(key = QuickFilterId.Tonight.name, contentType = "grid-filter-chip") {
            QuickChip(label = "Tonight", active = filters.tonightAvailable, onClick = { onToggled(QuickFilterId.Tonight) })
        }
        item(key = QuickFilterId.Verified.name, contentType = "grid-filter-chip") {
            QuickChip(label = "Verified", active = filters.verified, onClick = { onToggled(QuickFilterId.Verified) })
        }
        // Plan G2-3: the swinger ask. Hides men with no partner shown to this viewer; men visibly
        // linked to a partner still show (FILTERS_SPEC "No single men"). Last, so the row still opens
        // with "who is around now" and this reads as the couples' shortcut at the end.
        item(key = QuickFilterId.NoSingleMen.name, contentType = "grid-filter-chip") {
            QuickChip(
                label = NO_SINGLE_MEN_LABEL,
                active = filters.noSingleMen,
                onClick = { onToggled(QuickFilterId.NoSingleMen) },
                modifier = Modifier.testTag(GridFilterTags.NO_SINGLE_MEN_CHIP),
            )
        }
    }
}

/** A one-tap filter chip: an active one adds a check, so the state never rests on colour alone. */
@Composable
private fun QuickChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PynapliPill(
        text = label,
        selected = active,
        onClick = onClick,
        leading =
            if (active) {
                { ChipIcon(Icons.Filled.Check) }
            } else {
                null
            },
        modifier = modifier,
    )
}

@Composable
private fun ChipIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = PynapliColors.Accent,
        modifier = Modifier.size(16.dp),
    )
}

internal object GridFilterTags {
    const val QUICK_ROW: String = "grid-quick-filter-row"
    const val OPEN_SHEET: String = "grid-filters-open"
    const val SHEET: String = "grid-filters-sheet"
    const val APPLY: String = "grid-filters-apply"
    const val CLEAR_ALL: String = "grid-filters-clear-all"
    const val OPEN_TONIGHT: String = "grid-filters-open-tonight"
    const val FRESH_CHIP: String = "grid-quick-fresh"
    const val NO_SINGLE_MEN_CHIP: String = "grid-quick-no-single-men"
    const val PARTNER_SECTION: String = "grid-filters-partner"
}

/** The quick chip and its sheet twin share one name. */
internal const val NO_SINGLE_MEN_LABEL = "No single men"
