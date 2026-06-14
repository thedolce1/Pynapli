package com.pynapli.server.notifications

import com.pynapli.shared.network.dto.NotificationDetailLevel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun NotificationPreferencesRecord.allows(kind: String): Boolean =
    when (kind) {
        NotificationKind.MESSAGE -> messagesEnabled
        NotificationKind.MATCH -> matchesEnabled
        NotificationKind.FLIRT -> activityEnabled && flirtsEnabled
        NotificationKind.PROFILE_VIEW -> activityEnabled
        NotificationKind.ALBUM_REQUEST -> activityEnabled && albumRequestsEnabled
        NotificationKind.PROFILE_LINK_INVITE,
        NotificationKind.PROFILE_LINK_ACCEPTED,
        NotificationKind.PARTNER_CHAT_INVITE,
        NotificationKind.TONIGHT_INVITE,
        -> partnerInvitesEnabled
        NotificationKind.TONIGHT_REMINDER -> activityEnabled
        NotificationKind.SYSTEM_SAFETY -> systemSafetyEnabled
        else -> false
    }

internal fun notificationCopy(
    kind: String,
    detailLevel: String,
): NotificationCopy {
    if (detailLevel == NotificationDetailLevel.DISCREET) {
        return NotificationCopy(
            title = "Pynapli",
            body = "You have a new update.",
        )
    }

    return when (kind) {
        NotificationKind.MESSAGE -> NotificationCopy("New message", "Someone sent you a message.")
        NotificationKind.MATCH -> NotificationCopy("New match", "You have a new match.")
        NotificationKind.FLIRT -> NotificationCopy("New flirt", "Someone flirted with you.")
        NotificationKind.PROFILE_VIEW -> NotificationCopy("New activity", "Someone viewed your profile.")
        NotificationKind.ALBUM_REQUEST ->
            NotificationCopy(
                "Private album request",
                "Someone requested access to your private album.",
            )
        NotificationKind.PROFILE_LINK_INVITE ->
            NotificationCopy(
                "Profile link invite",
                "Someone invited you to link profiles.",
            )
        NotificationKind.PROFILE_LINK_ACCEPTED ->
            NotificationCopy(
                "Profile link accepted",
                "Your profile link invite was accepted.",
            )
        NotificationKind.PARTNER_CHAT_INVITE ->
            NotificationCopy(
                "Chat invite",
                "Your linked partner invited you to a chat.",
            )
        NotificationKind.TONIGHT_INVITE ->
            NotificationCopy(
                "Playing Tonight invite",
                "Your linked partner wants to go live tonight.",
            )
        NotificationKind.TONIGHT_REMINDER ->
            NotificationCopy(
                "Playing Tonight ending soon",
                "Your session ends in about 15 minutes.",
            )
        NotificationKind.SYSTEM_SAFETY -> NotificationCopy("Pynapli safety", "You have a safety update.")
        else -> NotificationCopy("Pynapli", "You have a new update.")
    }
}

internal fun Map<String, String>.toPayloadJson(
    kind: String,
    detailLevel: String,
): String {
    val payload =
        buildMap<String, JsonElement> {
            put("kind", JsonPrimitive(kind))
            put("detailLevel", JsonPrimitive(detailLevel))
            this@toPayloadJson.forEach { (key, value) ->
                put(key, JsonPrimitive(value))
            }
        }
    return JsonObject(payload).toString()
}

internal fun NotificationEventDraft.throttlePolicy(kind: String): NotificationThrottlePolicy? =
    when (kind) {
        NotificationKind.MESSAGE ->
            subjectId?.let { conversationId ->
                NotificationThrottlePolicy(
                    key = "message:$recipientUserId:conversation:$conversationId",
                    windowSeconds = MESSAGE_THREAD_WINDOW_SECONDS,
                )
            }
        NotificationKind.FLIRT ->
            actorUserId?.let { actorUserId ->
                NotificationThrottlePolicy(
                    key = "flirt:$recipientUserId:actor:$actorUserId",
                    windowSeconds = SOCIAL_ACTOR_WINDOW_SECONDS,
                )
            }
        NotificationKind.PROFILE_VIEW ->
            NotificationThrottlePolicy(
                key = "profile_view:$recipientUserId",
                windowSeconds = PROFILE_VIEW_WINDOW_SECONDS,
            )
        NotificationKind.ALBUM_REQUEST ->
            actorUserId?.let { actorUserId ->
                NotificationThrottlePolicy(
                    key = "album_request:$recipientUserId:actor:$actorUserId",
                    windowSeconds = SOCIAL_ACTOR_WINDOW_SECONDS,
                )
            }
        else -> null
    }
