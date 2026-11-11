package com.pynapli.server.notifications

import com.pynapli.shared.network.dto.NotificationDetailLevel

internal const val MIN_PUSH_TOKEN_LENGTH = 16
internal const val MAX_PUSH_TOKEN_LENGTH = 4096
internal const val MAX_APP_VERSION_LENGTH = 40
internal const val MAX_LOCALE_LENGTH = 32
internal const val MAX_TIMEZONE_LENGTH = 64
internal const val MIN_QUIET_HOURS_MINUTE = 0
internal const val MAX_QUIET_HOURS_MINUTE = 1439
internal const val MESSAGE_THREAD_WINDOW_SECONDS = 90L
internal const val SOCIAL_ACTOR_WINDOW_SECONDS = 6L * 60L * 60L
internal const val PROFILE_VIEW_WINDOW_SECONDS = 6L * 60L * 60L

internal val NOTIFICATION_DETAIL_LEVELS =
    setOf(
        NotificationDetailLevel.DISCREET,
        NotificationDetailLevel.STANDARD,
    )
internal val PUSH_PLATFORMS = setOf("android", "ios")
internal val NOTIFICATION_KINDS =
    setOf(
        NotificationKind.MESSAGE,
        NotificationKind.MATCH,
        NotificationKind.FLIRT,
        NotificationKind.PROFILE_VIEW,
        NotificationKind.ALBUM_REQUEST,
        NotificationKind.PROFILE_LINK_INVITE,
        NotificationKind.PROFILE_LINK_ACCEPTED,
        NotificationKind.PARTNER_CHAT_INVITE,
        NotificationKind.TONIGHT_INVITE,
        NotificationKind.TONIGHT_REMINDER,
        NotificationKind.PSYNC_INVITE,
        NotificationKind.SYSTEM_SAFETY,
    )
