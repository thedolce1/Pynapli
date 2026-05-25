package com.pynapli.app.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.shared.distance.formatDistanceForUnit
import com.pynapli.shared.network.dto.ActivityEventDto

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
    onDismissEvent: (String) -> Unit
) {
    val distanceLabel = formatDistanceForUnit(
        distanceMeters = item.distanceMeters,
        fallbackLabel = item.distanceLabel,
        distanceUnit = distanceUnit
    )
    val isNew = item.isUnreadBadgeActivity()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (focused) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else if (isNew) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                } else {
                    Color.Transparent
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ActivityAvatar(photoUrl = item.actorPhotoUrl)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = item.actorDisplayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isNew) {
                    ActivityNewPill()
                }
                Text(
                    text = item.occurredAt.compactTimeLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = item.activityText(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                distanceLabel?.let { distance ->
                    Text(
                        text = distance,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
            AlbumRequestActions(
                item = item,
                pendingAction = pendingAction,
                expanded = albumReviewExpanded,
                onReviewAlbumRequest = onReviewAlbumRequest,
                onAlbumRequestDecision = onAlbumRequestDecision
            )
            ActivityPrimaryAction(
                item = item,
                onOpenMatch = onOpenMatch,
                onOpenPartnerChatInvite = onOpenPartnerChatInvite
            )
        }
        IconButton(
            onClick = { onDismissEvent(item.eventId) },
            enabled = !pendingDismiss,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
        ) {
            if (pendingDismiss) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss activity",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ActivityNewPill() {
    Text(
        text = "New",
        color = MaterialTheme.colorScheme.onPrimary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun AlbumRequestActions(
    item: ActivityEventDto,
    pendingAction: Boolean,
    expanded: Boolean,
    onReviewAlbumRequest: (String) -> Unit,
    onAlbumRequestDecision: (String, Boolean) -> Unit
) {
    if (item.type != "album_request") return

    when (item.albumRequestStatus) {
        "pending" -> {
            if (!expanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onReviewAlbumRequest(item.eventId) },
                        enabled = !pendingAction
                    ) {
                        Text(text = "Review", maxLines = 1)
                    }
                }
                return
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onAlbumRequestDecision(item.eventId, false) },
                    enabled = !pendingAction,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = if (pendingAction) "..." else "Deny", maxLines = 1)
                }
                Button(
                    onClick = { onAlbumRequestDecision(item.eventId, true) },
                    enabled = !pendingAction,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = if (pendingAction) "..." else "Grant", maxLines = 1)
                }
            }
        }

        "approved",
        "denied" -> {
            Text(
                text = if (item.albumRequestStatus == "approved") "Access granted" else "Request denied",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ActivityPrimaryAction(
    item: ActivityEventDto,
    onOpenMatch: (ActivityEventDto) -> Unit,
    onOpenPartnerChatInvite: (String) -> Unit
) {
    when (item.type) {
        "match" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Button(onClick = { onOpenMatch(item) }) {
                    Text(text = "Message", maxLines = 1)
                }
            }
        }

        "partner_chat_invite" -> {
            val conversationId = item.conversationId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Button(
                    onClick = { if (conversationId != null) onOpenPartnerChatInvite(conversationId) },
                    enabled = conversationId != null
                ) {
                    Text(text = "Open invite", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ActivityAvatar(photoUrl: String?) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF2B2622), Color(0xFF6D5D4F))
                )
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (photoUrl != null) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

private fun ActivityEventDto.activityText(): String = when (type) {
    "profile_view" -> if (count > 1) "viewed you ${count}x" else "viewed you"
    "flirt" -> if (count > 1) "sent Sparks ${count}x" else "sent a Spark"
    "album_request" -> if (count > 1) "requested album access ${count}x" else "requested album access"
    "swipe_like" -> "liked you"
    "pineapple_like" -> "sent a Pineapple"
    "intro_note" -> "sent an Intro Note"
    "match" -> "matched with you"
    "partner_chat_invite" -> "sent a chat invite"
    else -> "interacted with you"
}

private fun ActivityEventDto.isUnreadBadgeActivity(): Boolean =
    !seen && when (type) {
        "flirt",
        "swipe_like",
        "pineapple_like",
        "intro_note",
        "match",
        "partner_chat_invite" -> true
        "album_request" -> albumRequestStatus == null || albumRequestStatus == "pending"
        else -> false
    }

private fun String.compactTimeLabel(): String =
    if (length >= 16 && this[10] == 'T') substring(11, 16) else ""
