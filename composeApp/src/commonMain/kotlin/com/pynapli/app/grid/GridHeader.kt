package com.pynapli.app.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliHeaderActionButton
import com.pynapli.app.design.PynapliHeaderAvatar
import com.pynapli.app.design.PynapliScreenHeader
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.DarkMode
import com.pynapli.app.design.icons.filled.FilterList
import com.pynapli.app.design.icons.filled.LocationOn
import com.pynapli.app.design.icons.filled.Refresh

@Composable
internal fun GridHeader(
    locationLabel: String,
    profileImageUrl: String?,
    onProfileIconClick: () -> Unit,
    onOpenTonight: () -> Unit = {},
    onOpenFilters: () -> Unit = {},
    // Pointer-device refresh (WEB_PWA_GAMEPLAN.md W2.1): a mouse wheel can't pull to
    // refresh, so wide windows surface an explicit button. Null (phones) hides it.
    onRefresh: (() -> Unit)? = null,
) {
    PynapliScreenHeader(
        leading = {
            PynapliHeaderAvatar(
                onClick = onProfileIconClick,
                imageUrl = profileImageUrl,
                online = true,
            )
        },
        center = { GridLocationPill(locationLabel) },
        actions = {
            onRefresh?.let { refresh ->
                PynapliHeaderActionButton(
                    icon = Icons.Filled.Refresh,
                    contentDescription = "Refresh nearby profiles",
                    onClick = refresh,
                    modifier = Modifier.testTag("grid-refresh"),
                )
            }
            PynapliHeaderActionButton(
                icon = Icons.Filled.DarkMode,
                contentDescription = "Playing Tonight",
                onClick = onOpenTonight,
                modifier = Modifier.testTag("grid-open-tonight"),
                tint = PynapliColors.Accent,
            )
            PynapliHeaderActionButton(
                icon = Icons.Filled.FilterList,
                contentDescription = "Filters",
                onClick = onOpenFilters,
                modifier = Modifier.testTag(GridFilterTags.HEADER_OPEN_SHEET),
            )
        },
    )
}

@Composable
private fun GridLocationPill(locationLabel: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(PynapliShape.PillShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = locationLabel,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
