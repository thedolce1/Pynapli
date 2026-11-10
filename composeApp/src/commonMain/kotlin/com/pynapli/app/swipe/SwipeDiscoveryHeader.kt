package com.pynapli.app.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliPill
import com.pynapli.app.design.PynapliScreenHeader
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Lock
import com.pynapli.app.design.icons.filled.Style

internal object SwipeHeaderTags {
    const val OPEN_PSYNC = "swipe_open_psync"
}

@Composable
internal fun SwipeHeader(
    activeFilterCount: Int,
    travelCityName: String?,
    onOpenFilters: () -> Unit,
    onOpenTravel: () -> Unit,
    onOpenPsync: (() -> Unit)? = null,
) {
    // Slim single-row header: the bottom nav already labels this tab "Swipe", so the tall
    // title/subtitle/icon block is dropped to give the card deck the maximum vertical space.
    // Psync / Travel / Filters stay as compact text actions; Travel surfaces the active city.
    // insets = false: the Swipe content column already applies the status-bar inset + padding,
    // so the shared header contributes only the slot layout (leading icon, title, text actions).
    PynapliScreenHeader(
        insets = false,
        leading = {
            Icon(
                imageVector = Icons.Filled.Style,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        },
        title = "Swipe",
        actions = {
            // TextButton defaults to colorScheme.primary, which made all three of these gold at
            // equal weight — competing with each other and with the gold brand glyph, so nothing
            // read as primary. They are quiet actions by default; the accent is spent only where it
            // carries information, i.e. when Travel or Filters are actually ACTIVE. Same rule the
            // Grid chips already follow.
            onOpenPsync?.let { openPsync ->
                TextButton(
                    onClick = openPsync,
                    colors = ButtonDefaults.textButtonColors(contentColor = PynapliColors.TextSecondary),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag(SwipeHeaderTags.OPEN_PSYNC),
                ) {
                    Text(text = "Psync", maxLines = 1, style = MaterialTheme.typography.labelLarge)
                }
            }
            TextButton(
                onClick = onOpenTravel,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor =
                            if (travelCityName != null) PynapliColors.Accent else PynapliColors.TextSecondary,
                    ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.testTag(TravelModeTags.OPEN_SHEET),
            ) {
                Text(text = travelCityName ?: "Travel", maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
            TextButton(
                onClick = onOpenFilters,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor =
                            if (activeFilterCount > 0) PynapliColors.Accent else PynapliColors.TextSecondary,
                    ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.testTag(SwipeFilterTags.OPEN_SHEET),
            ) {
                Text(
                    text = if (activeFilterCount > 0) "Filters ($activeFilterCount)" else "Filters",
                    maxLines = 1,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
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
            item(
                key = filter.type.name,
                contentType = "swipe-filter-chip",
            ) {
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
                .clip(PynapliShape.CardShape)
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
