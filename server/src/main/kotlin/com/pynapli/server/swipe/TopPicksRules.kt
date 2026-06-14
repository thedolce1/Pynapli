package com.pynapli.server.swipe

import java.time.Duration
import java.time.Instant
import java.util.UUID

internal val TOP_PICKS_REFRESH_INTERVAL: Duration = Duration.ofHours(24)
internal const val TOP_PICKS_RADIUS_METERS: Int = 50_000
internal const val TOP_PICKS_FREE_TIER_LIMIT: Int = 1
internal const val TOP_PICKS_PAID_TIER_LIMIT: Int = 10
internal const val TOP_PICKS_MAX_VISIBLE_PHOTOS = 5
internal const val TOP_PICKS_MAX_VISIBLE_TAGS = 3
internal const val TOP_PICKS_MAX_SHORT_BIO_CHARS = 120

internal data class TopPickCardTokenBinding(
    val position: Int,
    val targetUserId: UUID,
    val rawToken: String,
    val tokenHash: String,
    val cardRecord: SwipeCardRecord,
    val dealtAt: Instant,
)
