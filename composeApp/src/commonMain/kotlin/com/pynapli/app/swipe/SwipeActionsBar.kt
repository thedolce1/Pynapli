package com.pynapli.app.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun SwipeActions(
    actionEnabled: Boolean,
    rewindEnabled: Boolean,
    isSubmitting: Boolean,
    onRewind: () -> Unit,
    onPass: () -> Unit,
    onIntroNote: () -> Unit,
    onPineappleLike: () -> Unit,
    onLike: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, alignment = Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SwipeActionButton(
            enabled = rewindEnabled,
            isSubmitting = isSubmitting,
            testTag = SwipeTestTags.REWIND_ACTION,
            icon = Icons.Filled.Refresh,
            contentDescription = "Rewind",
            background = MaterialTheme.colorScheme.surfaceVariant,
            iconTint = MaterialTheme.colorScheme.primary,
            onClick = onRewind
        )
        SwipeActionButton(
            enabled = actionEnabled,
            isSubmitting = isSubmitting,
            testTag = SwipeTestTags.PASS_ACTION,
            icon = Icons.Filled.Close,
            contentDescription = "Pass",
            background = Color(0x33EE6650),
            iconTint = Color(0xFFE85A4A),
            onClick = onPass
        )
        SwipeActionButton(
            enabled = actionEnabled,
            isSubmitting = isSubmitting,
            testTag = SwipeTestTags.INTRO_NOTE_ACTION,
            icon = Icons.Filled.Edit,
            contentDescription = "Intro Note",
            background = MaterialTheme.colorScheme.surfaceVariant,
            iconTint = MaterialTheme.colorScheme.primary,
            onClick = onIntroNote
        )
        SwipeActionButton(
            enabled = actionEnabled,
            isSubmitting = isSubmitting,
            testTag = SwipeTestTags.PINEAPPLE_LIKE_ACTION,
            icon = Icons.Filled.Star,
            contentDescription = "Pineapple Like",
            background = Color(0x33F2B84B),
            iconTint = Color(0xFFF2B84B),
            onClick = onPineappleLike
        )
        SwipeActionButton(
            enabled = actionEnabled,
            isSubmitting = isSubmitting,
            testTag = SwipeTestTags.LIKE_ACTION,
            icon = Icons.Filled.Favorite,
            contentDescription = "Like",
            background = Color(0x331FBF7B),
            iconTint = Color(0xFF1FBF7B),
            onClick = onLike
        )
    }
}

@Composable
private fun SwipeActionButton(
    enabled: Boolean,
    isSubmitting: Boolean,
    testTag: String,
    icon: ImageVector,
    contentDescription: String,
    background: Color,
    iconTint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, iconTint.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(56.dp).testTag(testTag)
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    color = iconTint,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
