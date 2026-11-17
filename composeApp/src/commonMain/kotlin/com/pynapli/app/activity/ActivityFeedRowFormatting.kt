package com.pynapli.app.activity

import com.pynapli.shared.network.dto.ActivityEventDto

internal fun ActivityEventDto.activityText(): String =
    when (type) {
        "profile_view" -> if (count > 1) "viewed you ${count}x" else "viewed you"
        "flirt" -> if (count > 1) "sent Sparks ${count}x" else "sent a Spark"
        "album_request" -> if (count > 1) "requested album access ${count}x" else "requested album access"
        "swipe_like" -> if (pairDisplayName != null) "liked you together" else "liked you"
        "pineapple_like" ->
            when {
                pairDisplayName != null -> "sent you Pineapples together"
                noteText != null -> "sent a Pineapple with a note"
                else -> "sent a Pineapple"
            }
        "match" -> "matched with you"
        "album_granted" -> "shared their private album with you"
        "partner_chat_invite" -> "sent a chat invite"
        "psync_invite" -> "invited you to Psync"
        "psync_started" -> "started Psync with you"
        "psync_recap" -> "finished Psync with you. Your recap is ready"
        "psync_expired" -> "Psync session expired"
        else -> "interacted with you"
    }

/** "Maya & Alex" for a like a linked couple made together in Psync; otherwise the actor's name. */
internal fun ActivityEventDto.displayNames(): String = pairDisplayName?.let { "$actorDisplayName & $it" } ?: actorDisplayName

internal fun ActivityEventDto.isUnreadBadgeActivity(): Boolean =
    !seen &&
        when (type) {
            "flirt",
            "swipe_like",
            "pineapple_like",
            "match",
            "partner_chat_invite",
            "psync_invite",
            "album_granted",
            -> true
            "album_request" -> albumRequestStatus == null || albumRequestStatus == "pending"
            else -> false
        }

internal fun String.compactTimeLabel(): String = if (length >= 16 && this[10] == 'T') substring(11, 16) else ""
