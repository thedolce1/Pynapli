package com.pynapli.app.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliEmptyState
import com.pynapli.app.design.PynapliHeaderActionButton
import com.pynapli.app.design.PynapliLayout
import com.pynapli.app.design.PynapliPill
import com.pynapli.app.design.PynapliScreenHeader
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Lock
import com.pynapli.app.design.icons.filled.Notifications
import com.pynapli.app.design.icons.filled.Refresh

@Composable
internal fun ActivityGateRow(
    gatedCount: Int,
    onOpenStore: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(PynapliShape.CardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f), PynapliShape.CardShape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$gatedCount more waiting",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Select unlocks the full Activity feed.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onOpenStore, modifier = Modifier.handCursor()) {
            Text(text = "Select", maxLines = 1)
        }
    }
}

@Composable
internal fun ActivityInlineMessage(
    message: String?,
    isError: Boolean,
) {
    if (message == null) return
    Text(
        text = message,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
internal fun ActivityTabRow(
    selectedTab: ActivityTabUi,
    onTabSelected: (ActivityTabUi) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = PynapliLayout.ScreenPadding, end = PynapliLayout.ScreenPadding, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ActivityTabUi.entries.forEach { tab ->
            item(key = tab.name) {
                PynapliPill(
                    text = tab.label,
                    selected = tab == selectedTab,
                    onClick = { onTabSelected(tab) },
                )
            }
        }
    }
}

/**
 * Count context strip under the Activity tabs (handoff `Other.jsx` GridSectionHeader): a bold total
 * for the selected tab + the tab label, reinforcing the FOMO model (e.g. "9 VIEWS" with most gated).
 * Restrained meta styled to match the Grid's `GridContextStrip` for cross-surface cohesion.
 */
@Composable
internal fun ActivityContextStrip(
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = count.toString(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label.uppercase(),
            color = PynapliColors.TextTertiary,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.4.sp,
        )
    }
}

@Composable
internal fun ActivityHeader(
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    PynapliScreenHeader(
        title = "Activity",
        actions = {
            PynapliHeaderActionButton(
                icon = Icons.Filled.Refresh,
                contentDescription = "Refresh activity",
                onClick = onRefresh,
                enabled = !loading,
            )
        },
    )
}

@Composable
internal fun ActivityLoadMoreRow(
    loading: Boolean,
    onLoadMore: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        OutlinedButton(
            onClick = onLoadMore,
            enabled = !loading,
            modifier = Modifier.handCursor(enabled = !loading),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier =
                        Modifier
                            .padding(end = 8.dp)
                            .size(16.dp),
                )
            }
            Text(text = if (loading) "Loading" else "Load more", maxLines = 1)
        }
    }
}

@Composable
internal fun EmptyActivityState(
    label: String,
    modifier: Modifier = Modifier,
) {
    PynapliEmptyState(
        icon = Icons.Filled.Notifications,
        title = label,
        modifier = modifier,
    )
}

internal fun ActivityTabUi.emptyLabel(): String =
    when (this) {
        ActivityTabUi.Flirts -> "No flirts yet."
        ActivityTabUi.Views -> "No views yet."
        ActivityTabUi.Matches -> "No matches yet."
        ActivityTabUi.Requests -> "No requests yet."
    }
