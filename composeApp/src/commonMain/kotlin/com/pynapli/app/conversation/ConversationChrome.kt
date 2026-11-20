package com.pynapli.app.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliPlaceholderGradients
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.automirrored.filled.ArrowBack
import com.pynapli.app.design.icons.filled.Close
import com.pynapli.app.design.icons.filled.Person
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationParticipantDto

@Composable
internal fun FullScreenConversationHeader(
    state: ConversationUiState,
    onBack: () -> Unit,
    onOpenParticipantProfile: (ConversationParticipantDto) -> Unit,
    onMetInPerson: (String) -> Unit = {},
    onMetInPersonMenuOpened: () -> Unit = {},
) {
    val participant = state.detail?.conversation?.primaryOtherParticipant(state.viewerUserId)
    val title = state.conversationTitle()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .testTag(ConversationTestTags.fullScreenHeader),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.handCursor()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            ConversationAvatar(
                participant = participant,
                online = participant?.online == true,
                onClick = participant?.let { selected -> { onOpenParticipantProfile(selected) } },
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .then(
                            if (participant != null) {
                                Modifier.handCursor().clickable { onOpenParticipantProfile(participant) }
                            } else {
                                Modifier
                            },
                        ),
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (participant?.online == true) "Online" else "Active recently",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = {
                    if (participant != null) {
                        onOpenParticipantProfile(participant)
                    }
                },
                enabled = participant != null,
                modifier = Modifier.handCursor(enabled = participant != null),
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = "Open profile",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            ConversationOverflowButton(menu = state.metInPersonMenuUi(), onMenuOpened = onMetInPersonMenuOpened, onMetInPerson = onMetInPerson)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    }
}

@Composable
private fun ConversationAvatar(
    participant: ConversationParticipantDto?,
    online: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val initial =
        participant
            ?.displayName
            ?.trim()
            ?.firstOrNull()
            ?.uppercaseChar()
            ?.toString() ?: ""
    Box(modifier = Modifier.size(46.dp)) {
        Box(
            modifier =
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .then(if (onClick != null) Modifier.handCursor().clickable(onClick = onClick) else Modifier)
                    .background(PynapliPlaceholderGradients.messagingAvatar)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val photoUrl = participant?.photoUrl
            if (photoUrl != null) {
                AsyncImage(
                    model = photoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                Text(
                    text = initial,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (online) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(PynapliColors.Success)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
            )
        }
    }
}

@Composable
internal fun ConversationContextStrip(conversation: ConversationDto?) {
    if (conversation == null) return
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.68f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = conversation.origin.conversationOriginLabel(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Text(
            text = conversation.updatedAt.conversationUpdatedLabel(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.36f))
}

@Composable
internal fun ConversationHeader(
    title: String,
    onDismiss: () -> Unit,
    // The one other person in a direct chat: tapping their name opens their profile (block,
    // report, hide live there). Null for groups and while loading.
    participant: ConversationParticipantDto? = null,
    onOpenParticipantProfile: (ConversationParticipantDto) -> Unit = {},
    // Server-offered "We've met in person" items; the overflow button shows only when there are any.
    // The conversation's overflow menu ("We've met in person"), fetched when opened; null hides it.
    metInPersonMenu: MetInPersonMenuUi? = null,
    onMetInPersonMenuOpened: () -> Unit = {},
    onMetInPerson: (String) -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .weight(1f)
                    .then(
                        if (participant != null) {
                            Modifier.handCursor().clickable { onOpenParticipantProfile(participant) }
                        } else {
                            Modifier
                        },
                    ),
        )
        if (participant != null) {
            IconButton(onClick = { onOpenParticipantProfile(participant) }, modifier = Modifier.handCursor()) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = "Open profile",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        metInPersonMenu?.let { menu ->
            ConversationOverflowButton(menu = menu, onMenuOpened = onMetInPersonMenuOpened, onMetInPerson = onMetInPerson)
        }
        IconButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close conversation",
            )
        }
    }
}
