package com.pynapli.app.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Person
import com.pynapli.shared.network.dto.ActivityEventDto

@Composable
internal fun ActivityNewPill() {
    Text(
        text = "New",
        color = MaterialTheme.colorScheme.onPrimary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier =
            Modifier
                .clip(PynapliShape.PillShape)
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun AlbumRequestActions(
    item: ActivityEventDto,
    pendingAction: Boolean,
    expanded: Boolean,
    onReviewAlbumRequest: (String) -> Unit,
    onAlbumRequestDecision: (String, Boolean) -> Unit,
) {
    if (item.type != "album_request") return

    when (item.albumRequestStatus) {
        "pending" -> {
            if (!expanded) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onReviewAlbumRequest(item.eventId) },
                        enabled = !pendingAction,
                        modifier = Modifier.handCursor(enabled = !pendingAction),
                    ) {
                        Text(text = "Review", maxLines = 1)
                    }
                }
                return
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onAlbumRequestDecision(item.eventId, false) },
                    enabled = !pendingAction,
                    modifier = Modifier.weight(1f).handCursor(enabled = !pendingAction),
                ) {
                    Text(text = if (pendingAction) "..." else "Deny", maxLines = 1)
                }
                Button(
                    onClick = { onAlbumRequestDecision(item.eventId, true) },
                    enabled = !pendingAction,
                    modifier = Modifier.weight(1f).handCursor(enabled = !pendingAction),
                ) {
                    Text(text = if (pendingAction) "..." else "Grant", maxLines = 1)
                }
            }
        }

        "approved",
        "denied",
        -> {
            Text(
                text = if (item.albumRequestStatus == "approved") "Access granted" else "Request denied",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun ActivityPrimaryAction(
    item: ActivityEventDto,
    onOpenMatch: (ActivityEventDto) -> Unit,
    onOpenPartnerChatInvite: (String) -> Unit,
    onOpenPsync: (String) -> Unit,
) {
    when (item.type) {
        "match" -> {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
            ) {
                Button(onClick = { onOpenMatch(item) }, modifier = Modifier.handCursor()) {
                    Text(text = "Message", maxLines = 1)
                }
            }
        }

        "partner_chat_invite" -> {
            val conversationId = item.conversationId
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
            ) {
                Button(
                    onClick = { if (conversationId != null) onOpenPartnerChatInvite(conversationId) },
                    enabled = conversationId != null,
                    modifier = Modifier.handCursor(enabled = conversationId != null),
                ) {
                    Text(text = "Open invite", maxLines = 1)
                }
            }
        }

        "psync_invite",
        "psync_started",
        "psync_recap",
        -> {
            val sessionId = item.psyncSessionId
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
            ) {
                Button(
                    onClick = { if (sessionId != null) onOpenPsync(sessionId) },
                    enabled = sessionId != null,
                    modifier = Modifier.handCursor(enabled = sessionId != null),
                ) {
                    Text(text = if (item.type == "psync_recap") "See recap" else "Open Psync", maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun ActivityAvatar(photoUrl: String?) {
    Box(
        modifier =
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(PynapliColors.WarmPlaceholderDark, PynapliColors.WarmPlaceholderLight),
                    ),
                ).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (photoUrl != null) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
