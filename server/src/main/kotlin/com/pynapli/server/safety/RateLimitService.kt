package com.pynapli.server.safety

import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class RateLimitService(
    private val repository: RateLimitRepository,
    private val riskSignalPublisher: RateLimitRiskSignalPublisher? = null,
) {
    suspend fun check(
        userId: UUID,
        action: String,
    ): RateLimitDecision {
        val policy = POLICIES[action] ?: throw SafetyException("INVALID_RATE_LIMIT_ACTION")
        val counter =
            repository.increment(
                key = policy.keyFor(userId),
                windowSeconds = policy.windowSeconds,
            )
        val decision =
            RateLimitDecision(
                allowed = counter.count <= policy.limit,
                action = policy.action,
                count = counter.count,
                limit = policy.limit,
                ttlSeconds = counter.ttlSeconds,
                windowSeconds = policy.windowSeconds,
            )

        if (!decision.allowed) {
            riskSignalPublisher?.let { publisher ->
                runCatching {
                    publisher.recordRateLimitHit(
                        userId = userId,
                        action = policy.action,
                        count = decision.count,
                        limit = policy.limit,
                        windowSeconds = policy.windowSeconds,
                    )
                }.onFailure { error ->
                    logger.warn("Failed to record rate-limit risk signal for user {}", userId, error)
                }
            }
        }

        return decision
    }

    suspend fun checkSubject(
        subject: String,
        action: String,
    ): RateLimitDecision {
        val policy = POLICIES[action] ?: throw SafetyException("INVALID_RATE_LIMIT_ACTION")
        val counter =
            repository.increment(
                key = policy.subjectKeyFor(subject),
                windowSeconds = policy.windowSeconds,
            )
        return RateLimitDecision(
            allowed = counter.count <= policy.limit,
            action = policy.action,
            count = counter.count,
            limit = policy.limit,
            ttlSeconds = counter.ttlSeconds,
            windowSeconds = policy.windowSeconds,
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RateLimitService::class.java)

        private val POLICIES =
            listOf(
                RateLimitPolicy(action = RateLimitedAction.AUTH_SIGNUP, limit = 10, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.AUTH_LOGIN, limit = 20, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.AUTH_REFRESH, limit = 120, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.REPORT, limit = 30, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.FLIRT, limit = 60, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.ALBUM_REQUEST, limit = 20, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.PROFILE_VIEW, limit = 240, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.SWIPE_ACTION, limit = 500, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.FIRST_MESSAGE, limit = 30, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.GRID_QUERY, limit = 720, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.MEDIA_UPLOAD, limit = 120, windowSeconds = ONE_HOUR_SECONDS),
                // Type-ahead: every debounced keystroke is a query, so the budget is generous.
                RateLimitPolicy(action = RateLimitedAction.TRAVEL_SEARCH, limit = 600, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.AGE_VERIFICATION_START, limit = 5, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.STORE_PURCHASE, limit = 60, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.BOOST_ACTIVATE, limit = 60, windowSeconds = ONE_HOUR_SECONDS),
                // PSYNC_SPEC PS-4: nudge cooldown — one nudge per minute per user (≈one live session).
                RateLimitPolicy(action = RateLimitedAction.PSYNC_NUDGE, limit = 1, windowSeconds = NUDGE_COOLDOWN_SECONDS),
                // Profile/settings edits run validation + the bio scanner on every call; generous
                // for legitimate editing, but caps scripted spam of the write/scan path.
                RateLimitPolicy(action = RateLimitedAction.PROFILE_UPDATE, limit = 60, windowSeconds = ONE_HOUR_SECONDS),
            ).associateBy { it.action }

        private const val ONE_HOUR_SECONDS = 3_600L
        private const val NUDGE_COOLDOWN_SECONDS = 60L
    }
}

object RateLimitedAction {
    const val AUTH_SIGNUP = "auth_signup"
    const val AUTH_LOGIN = "auth_login"
    const val AUTH_REFRESH = "auth_refresh"
    const val REPORT = "report"
    const val FLIRT = "flirt"
    const val ALBUM_REQUEST = "album_request"
    const val PROFILE_VIEW = "profile_view"
    const val SWIPE_ACTION = "swipe_action"
    const val FIRST_MESSAGE = "first_message"
    const val GRID_QUERY = "grid_query"
    const val MEDIA_UPLOAD = "media_upload"
    const val TRAVEL_SEARCH = "travel_search"
    const val AGE_VERIFICATION_START = "age_verification_start"
    const val STORE_PURCHASE = "store_purchase"
    const val BOOST_ACTIVATE = "boost_activate"
    const val PSYNC_NUDGE = "psync_nudge"
    const val PROFILE_UPDATE = "profile_update"
}

data class RateLimitDecision(
    val allowed: Boolean,
    val action: String,
    val count: Long,
    val limit: Long,
    val ttlSeconds: Long,
    val windowSeconds: Long,
)

private data class RateLimitPolicy(
    val action: String,
    val limit: Long,
    val windowSeconds: Long,
) {
    fun keyFor(userId: UUID): String = "pynapli:rate:$action:$userId"

    fun subjectKeyFor(subject: String): String = "pynapli:rate:$action:subject:${subject.rateLimitHash()}"
}

private fun String.rateLimitHash(): String {
    val normalized = trim().lowercase().takeIf { it.isNotEmpty() } ?: "unknown"
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}
