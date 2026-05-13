package com.pynapli.server.safety

import org.slf4j.LoggerFactory
import java.util.UUID

class RateLimitService(
    private val repository: RateLimitRepository,
    private val riskSignalPublisher: RateLimitRiskSignalPublisher? = null
) {
    suspend fun check(userId: UUID, action: String): RateLimitDecision {
        val policy = POLICIES[action] ?: throw SafetyException("INVALID_RATE_LIMIT_ACTION")
        val counter = repository.increment(
            key = policy.keyFor(userId),
            windowSeconds = policy.windowSeconds
        )
        val decision = RateLimitDecision(
            allowed = counter.count <= policy.limit,
            action = policy.action,
            count = counter.count,
            limit = policy.limit,
            ttlSeconds = counter.ttlSeconds,
            windowSeconds = policy.windowSeconds
        )

        if (!decision.allowed) {
            riskSignalPublisher?.let { publisher ->
                runCatching {
                    publisher.recordRateLimitHit(
                        userId = userId,
                        action = policy.action,
                        count = decision.count,
                        limit = policy.limit,
                        windowSeconds = policy.windowSeconds
                    )
                }.onFailure { error ->
                    logger.warn("Failed to record rate-limit risk signal for user {}", userId, error)
                }
            }
        }

        return decision
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RateLimitService::class.java)

        private val POLICIES = listOf(
            RateLimitPolicy(action = RateLimitedAction.REPORT, limit = 30, windowSeconds = ONE_HOUR_SECONDS),
            RateLimitPolicy(action = RateLimitedAction.FLIRT, limit = 60, windowSeconds = ONE_HOUR_SECONDS),
            RateLimitPolicy(action = RateLimitedAction.ALBUM_REQUEST, limit = 20, windowSeconds = ONE_HOUR_SECONDS),
            RateLimitPolicy(action = RateLimitedAction.PROFILE_VIEW, limit = 240, windowSeconds = ONE_HOUR_SECONDS),
            RateLimitPolicy(action = RateLimitedAction.SWIPE_ACTION, limit = 500, windowSeconds = ONE_HOUR_SECONDS),
            RateLimitPolicy(action = RateLimitedAction.FIRST_MESSAGE, limit = 30, windowSeconds = ONE_HOUR_SECONDS),
            RateLimitPolicy(action = RateLimitedAction.GRID_QUERY, limit = 720, windowSeconds = ONE_HOUR_SECONDS)
        ).associateBy { it.action }

        private const val ONE_HOUR_SECONDS = 3_600L
    }
}

object RateLimitedAction {
    const val REPORT = "report"
    const val FLIRT = "flirt"
    const val ALBUM_REQUEST = "album_request"
    const val PROFILE_VIEW = "profile_view"
    const val SWIPE_ACTION = "swipe_action"
    const val FIRST_MESSAGE = "first_message"
    const val GRID_QUERY = "grid_query"
}

data class RateLimitDecision(
    val allowed: Boolean,
    val action: String,
    val count: Long,
    val limit: Long,
    val ttlSeconds: Long,
    val windowSeconds: Long
)

private data class RateLimitPolicy(
    val action: String,
    val limit: Long,
    val windowSeconds: Long
) {
    fun keyFor(userId: UUID): String = "pynapli:rate:$action:$userId"
}
