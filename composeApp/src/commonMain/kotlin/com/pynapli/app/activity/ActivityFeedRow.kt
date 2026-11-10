package com.pynapli.app.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Close
import com.pynapli.shared.distance.formatDistanceForUnit
import com.pynapli.shared.network.dto.ActivityEventDto

internal const val PLAYING_TONIGHT_SOURCE = "playing_tonight"
internal const val VIA_PLAYING_TONIGHT_TEST_TAG = "activity-via-playing-tonight"

@Composable
internal fun ActivityRow(
    item: ActivityEventDto,
    focused: Boolean,
    pendingAction: Boolean,
    albumReviewExpanded: Boolean,
    pendingDismiss: Boolean,
    distanceUnit: String,
    onReviewAlbumRequest: (String) -> Unit,
    onAlbumRequestDecision: (String, Boolean) -> Unit,
    onOpenMatch: (ActivityEventDto) -> Unit,
    onOpenPartnerChatInvite: (String) -> Unit,
    onDismissEvent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val distanceLabel =
        formatDistanceForUnit(
            distanceMeters = item.distanceMeters,
            fallbackLabel = item.distanceLabel,
            distanceUnit = distanceUnit,
        )
    val isNew = item.isUnreadBadgeActivity()

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    if (focused) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    } else if (isNew) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                    } else {
                        Color.Transparent
                    },
                ).padding(horizontal = 16.dp, vertical = 12.dp)
                // Read the actor + activity + distance as one item; the review/invite/dismiss
                // controls stay as their own focusable buttons.
                .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ActivityAvatar(photoUrl = item.actorPhotoUrl)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.actorDisplayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isNew) {
                    ActivityNewPill()
                }
                Text(
                    text = item.occurredAt.compactTimeLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.activityText(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                distanceLabel?.let { distance ->
                    Text(
                        text = distance,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
            if (item.source == PLAYING_TONIGHT_SOURCE) {
                ViaPlayingTonightChip()
            }
            AlbumRequestActions(
                item = item,
                pendingAction = pendingAction,
                expanded = albumReviewExpanded,
                onReviewAlbumRequest = onReviewAlbumRequest,
                onAlbumRequestDecision = onAlbumRequestDecision,
            )
            ActivityPrimaryAction(
                item = item,
                onOpenMatch = onOpenMatch,
                onOpenPartnerChatInvite = onOpenPartnerChatInvite,
            )
        }
        IconButton(
            onClick = { onDismissEvent(item.eventId) },
            enabled = !pendingDismiss,
            modifier =
                Modifier
                    // Compact 36dp visual, >=48dp touch target.
                    .minimumInteractiveComponentSize()
                    .size(36.dp)
                    .clip(CircleShape),
        ) {
            if (pendingDismiss) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss activity",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Small accent caption surfaced when a flirt originated from the Playing Tonight surface
 * (PLAYING_TONIGHT_SPEC: "Activity labels 'via Playing Tonight' where appropriate").
 */
@Composable
private fun ViaPlayingTonightChip() {
    Text(
        text = "via Playing Tonight",
        color = PynapliColors.Accent,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier =
            Modifier
                .padding(top = 4.dp)
                .clip(PynapliShape.PillShape)
                .background(PynapliColors.Accent.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .testTag(VIA_PLAYING_TONIGHT_TEST_TAG),
    )
}
