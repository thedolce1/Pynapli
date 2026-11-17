package com.pynapli.server.activity

internal const val ACTIVITY_SOURCE_PLAYING_TONIGHT_VALUE = "playing_tonight"
internal const val ACTIVITY_DEFAULT_LIMIT = 50
internal const val ACTIVITY_MAX_LIMIT = 100
internal const val ACTIVITY_MAX_MARK_SEEN_IDS = 100

internal val ACTIVITY_SWIPE_INTEREST_EVENT_TYPES = setOf("swipe_like", "pineapple_like")

class ActivityException(
    val code: String,
) : RuntimeException(code)

internal suspend fun ActivityBroadcaster?.publishActivityDelta(
    recipientUserId: java.util.UUID,
    eventType: String,
) {
    this?.emit(
        ActivityRealtimeDelta(
            recipientUserId = recipientUserId,
            type = eventType,
        ),
    )
}

internal fun activityNotificationPayload(
    eventType: String,
    extra: Map<String, String> = emptyMap(),
): Map<String, String> =
    extra +
        mapOf(
            "target" to "activity",
            "activityTab" to ActivityFeedTab.fromEventType(eventType).value,
            "activityEventType" to eventType,
        )
