package com.pynapli.server.subscriptions

import com.pynapli.server.safety.RateLimitService
import java.time.Instant
import java.util.UUID

class SubscriptionException(
    val code: String,
) : RuntimeException(code)

data class PlayStoreSubscriptionSyncSummary(
    val scanned: Int,
    val verified: Int,
    val active: Int,
    val grace: Int,
    val pending: Int,
    val expired: Int,
    val revoked: Int,
    val invalid: Int,
    val unavailable: Int,
)

internal suspend fun RateLimitService?.enforceSubscriptionRateLimit(
    userId: UUID,
    action: String,
) {
    if (this?.check(userId, action)?.allowed == false) throw SubscriptionException("RATE_LIMITED")
}

internal fun String.toUuidOrThrow(code: String): UUID =
    runCatching { UUID.fromString(trim()) }
        .getOrElse { throw SubscriptionException(code) }

internal fun String.toInstantOrThrow(code: String): Instant =
    runCatching { Instant.parse(trim()) }
        .getOrElse { throw SubscriptionException(code) }

internal fun String.validatedAdminActor(): String =
    trim().takeIf { it.length in 2..120 }
        ?: throw SubscriptionException("INVALID_ADMIN_ACTOR")

internal fun String.validatedAdminReason(): String =
    trim().takeIf { it.length in 2..500 }
        ?: throw SubscriptionException("ADMIN_REASON_REQUIRED")
