package com.pynapli.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.activity.ActivityTabUi
import com.pynapli.app.conversation.titleFor
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.ChatBubble
import com.pynapli.app.design.icons.filled.LocalFireDepartment
import com.pynapli.shared.network.dto.ActivityEventDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.settings.AppSettings

internal data class InAppNotificationUi(
    val id: String,
    val kind: InAppNotificationKind,
    val title: String,
    val body: String,
    val targetTab: PynapliTab,
    val activityTab: ActivityTabUi? = null,
)

internal enum class InAppNotificationKind(
    val icon: ImageVector,
) {
    Message(Icons.Filled.ChatBubble),
    Activity(Icons.Filled.LocalFireDepartment),
}

@Composable
internal fun InAppNotificationBanner(
    notification: InAppNotificationUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .handCursor()
                .clickable(onClick = onClick)
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = notification.kind.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = notification.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = notification.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun ConversationDto.toMessageNotification(
    viewerUserId: String?,
    settings: AppSettings,
): InAppNotificationUi {
    val standardDetail = settings.notificationDetail == AppSettings.NOTIFICATION_STANDARD
    return InAppNotificationUi(
        id = "message:$conversationId:${lastMessage?.messageId ?: updatedAt}:$unreadCount",
        kind = InAppNotificationKind.Message,
        title = if (standardDetail) titleFor(viewerUserId) else "New message",
        body =
            if (standardDetail) {
                lastMessage?.body?.cleanNotificationText() ?: "Open messages"
            } else {
                "Open messages"
            },
        targetTab = PynapliTab.Messages,
    )
}

internal fun ActivityEventDto.toActivityNotification(settings: AppSettings): InAppNotificationUi {
    val standardDetail = settings.notificationDetail == AppSettings.NOTIFICATION_STANDARD
    // Matches now live in Messages (the new-matches rail), so route them there.
    val matchNotification = type == "match"
    return InAppNotificationUi(
        id = "activity:$eventId",
        kind = InAppNotificationKind.Activity,
        title = if (standardDetail) actorDisplayName else "New activity",
        body = if (standardDetail) activityNotificationBody() else "Open Activity",
        targetTab = if (matchNotification) PynapliTab.Messages else PynapliTab.Activity,
        activityTab = if (matchNotification) null else activityTab(),
    )
}

private fun ActivityEventDto.activityNotificationBody(): String =
    when (type) {
        "profile_view", "viewed_profile" -> if (count > 1) "viewed you $count times" else "viewed you"
        "flirt" -> "sent a spark"
        "swipe_like" -> "liked you"
        "pineapple_like" -> "sent a Pineapple"
        "intro_note" -> "sent an Intro Note"
        "match" -> "matched with you"
        "album_request" -> "requested album access"
        "partner_chat_invite" -> "sent a chat invite"
        else -> "new activity"
    }

private fun ActivityEventDto.activityTab(): ActivityTabUi =
    when (type) {
        "profile_view", "viewed_profile" -> ActivityTabUi.Views
        "flirt", "swipe_like", "pineapple_like", "intro_note" -> ActivityTabUi.Flirts
        "album_request",
        "partner_chat_invite",
        "psync_invite",
        "psync_started",
        "psync_recap",
        "psync_expired",
        "playing_tonight",
        -> ActivityTabUi.Requests
        else -> ActivityTabUi.Flirts
    }

private fun String.cleanNotificationText(): String {
    val normalized = trim().replace('\n', ' ')
    return if (normalized.length <= NOTIFICATION_BODY_MAX_LENGTH) {
        normalized
    } else {
        normalized.take(NOTIFICATION_BODY_MAX_LENGTH - 3).trimEnd() + "..."
    }
}

private const val NOTIFICATION_BODY_MAX_LENGTH = 96
