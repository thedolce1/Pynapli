package com.pynapli.app.activity

import com.pynapli.shared.network.dto.ActivityEventDto

internal fun ActivityEventDto.activityText(): String =
    when (type) {
        "profile_view" -> if (count > 1) "viewed you ${count}x" else "viewed you"
        "flirt" -> if (count > 1) "sent Sparks ${count}x" else "sent a Spark"
        "album_request" -> if (count > 1) "requested album access ${count}x" else "requested album access"
        "swipe_like" -> "liked you"
        "pineapple_like" -> "sent a Pineapple"
        "intro_note" -> "sent an Intro Note"
        "match" -> "matched with you"
        "partner_chat_invite" -> "sent a chat invite"
        "psync_invite" -> "invited you to Psync"
        "psync_started" -> "started Psync with you"
        "psync_recap" -> "finished Psync with you. Your recap is ready"
        "psync_expired" -> "Psync session expired"
        else -> "interacted with you"
    }

internal fun ActivityEventDto.isUnreadBadgeActivity(): Boolean =
    !seen &&
        when (type) {
            "flirt",
            "swipe_like",
            "pineapple_like",
            "intro_note",
            "match",
            "partner_chat_invite",
            "psync_invite",
            -> true
            "album_request" -> albumRequestStatus == null || albumRequestStatus == "pending"
            else -> false
        }

internal fun String.compactTimeLabel(): String = if (length >= 16 && this[10] == 'T') substring(11, 16) else ""
