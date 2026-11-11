package com.pynapli.server.notifications

import java.util.UUID

class NotificationException(
    val code: String,
) : RuntimeException(code)

object NotificationKind {
    const val MESSAGE = "message"
    const val MATCH = "match"
    const val FLIRT = "flirt"
    const val PROFILE_VIEW = "profile_view"
    const val ALBUM_REQUEST = "album_request"
    const val PROFILE_LINK_INVITE = "profile_link_invite"
    const val PROFILE_LINK_ACCEPTED = "profile_link_accepted"
    const val PARTNER_CHAT_INVITE = "partner_chat_invite"
    const val TONIGHT_INVITE = "tonight_invite"
    const val TONIGHT_REMINDER = "tonight_reminder"
    const val PSYNC_INVITE = "psync_invite"
    const val SYSTEM_SAFETY = "system_safety"
}

data class NotificationEventDraft(
    val recipientUserId: UUID,
    val actorUserId: UUID?,
    val kind: String,
    val subjectId: UUID?,
    val idempotencyKey: String,
    val payload: Map<String, String> = emptyMap(),
)

internal data class NotificationCopy(
    val title: String,
    val body: String,
)

internal data class NotificationThrottlePolicy(
    val key: String,
    val windowSeconds: Long,
)

internal fun NotificationThrottlePolicy.bucketedIdempotencyKey(epochSecond: Long): String = "$key:${epochSecond / windowSeconds}"
