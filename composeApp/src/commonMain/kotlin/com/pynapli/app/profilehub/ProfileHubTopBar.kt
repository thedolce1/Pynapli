package com.pynapli.app.profilehub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliHeaderActionButton
import com.pynapli.app.design.PynapliHeaderTokens
import com.pynapli.app.design.PynapliScreenHeader
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.automirrored.filled.ArrowBack
import com.pynapli.app.design.icons.filled.Notifications

/**
 * Back-navigation top bar for the Profile hub and its sub-screens (Edit, panels). Delegates to the
 * canonical [PynapliScreenHeader] so the hub matches the core-surface header frame instead of
 * hand-rolling its own padding/title styling.
 */
@Composable
internal fun ProfileHubTopBar(
    title: String = "Profile",
    onBack: () -> Unit,
    actionCount: Int = 0,
    onNotificationsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // insets = false: these headers sit inside containers that already supply the horizontal inset
    // (the hub LazyColumn contentPadding / each panel's padding), so the frame only owns slot layout
    // while this wrapper adds the status-bar inset and vertical breathing room.
    PynapliScreenHeader(
        modifier =
            modifier
                .statusBarsPadding()
                .padding(vertical = 4.dp),
        insets = false,
        leading = {
            PynapliHeaderActionButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
            )
        },
        title = title,
        actions =
            onNotificationsClick?.let { onClick ->
                {
                    NotificationsHeaderAction(actionCount = actionCount, onClick = onClick)
                }
            },
    )
}

/** Notifications header action with an unread badge — the one header action that needs a badge. */
@Composable
private fun NotificationsHeaderAction(
    actionCount: Int,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier =
            Modifier
                .size(PynapliHeaderTokens.ActionButtonSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .handCursor(),
    ) {
        if (actionCount > 0) {
            BadgedBox(
                badge = {
                    Badge {
                        Text(text = if (actionCount > 9) "9+" else actionCount.toString())
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = "Notifications",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Icon(
                imageVector = Icons.Filled.Notifications,
                contentDescription = "Notifications",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
