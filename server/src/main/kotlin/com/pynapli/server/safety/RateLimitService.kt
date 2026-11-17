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
                // Each request sends an email; a handful an hour covers a mistyped inbox.
                RateLimitPolicy(action = RateLimitedAction.AUTH_PASSWORD_RESET, limit = 5, windowSeconds = ONE_HOUR_SECONDS),
                // Guessing a 6-digit code: each code also dies after 5 wrong tries.
                RateLimitPolicy(action = RateLimitedAction.AUTH_PASSWORD_RESET_VERIFY, limit = 15, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.AUTH_PASSWORD_CHANGE, limit = 10, windowSeconds = ONE_HOUR_SECONDS),
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
                // Request-level limits (RequestRateLimitPlugin). Per client IP before sign-in, sized for
                // a shared NAT (a venue's Wi-Fi), well below what scripted guessing needs.
                RateLimitPolicy(action = RateLimitedAction.AUTH_LOGIN_IP, limit = 100, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.AUTH_SIGNUP_IP, limit = 30, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.AUTH_PASSWORD_RESET_IP, limit = 30, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.ACCOUNT_APPEAL_IP, limit = 20, windowSeconds = ONE_HOUR_SECONDS),
                // Per session, far above real use: a fast chatter sends a few messages a minute.
                RateLimitPolicy(action = RateLimitedAction.MESSAGE_SEND, limit = 600, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.THREAD_CREATE, limit = 100, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.TYPING, limit = 2_400, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.PROFILE_LINK_INVITE, limit = 20, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.SESSION_CREATE, limit = 30, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.BLOCK_OR_HIDE, limit = 120, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.LOCATION_UPDATE, limit = 360, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.DATA_EXPORT, limit = 5, windowSeconds = ONE_DAY_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.APPEAL, limit = 10, windowSeconds = ONE_DAY_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.PUSH_REGISTER, limit = 60, windowSeconds = ONE_HOUR_SECONDS),
            ).associateBy { it.action }

        private const val ONE_HOUR_SECONDS = 3_600L
        private const val ONE_DAY_SECONDS = 86_400L
        private const val NUDGE_COOLDOWN_SECONDS = 60L
    }
}

object RateLimitedAction {
    const val AUTH_SIGNUP = "auth_signup"
    const val AUTH_LOGIN = "auth_login"
    const val AUTH_REFRESH = "auth_refresh"
    const val AUTH_PASSWORD_RESET = "auth_password_reset"
    const val AUTH_PASSWORD_RESET_VERIFY = "auth_password_reset_verify"
    const val AUTH_PASSWORD_CHANGE = "auth_password_change"
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
    const val AUTH_LOGIN_IP = "auth_login_ip"
    const val AUTH_SIGNUP_IP = "auth_signup_ip"
    const val AUTH_PASSWORD_RESET_IP = "auth_password_reset_ip"
    const val ACCOUNT_APPEAL_IP = "account_appeal_ip"
    const val MESSAGE_SEND = "message_send"
    const val THREAD_CREATE = "thread_create"
    const val TYPING = "typing"
    const val PROFILE_LINK_INVITE = "profile_link_invite"
    const val SESSION_CREATE = "session_create"
    const val BLOCK_OR_HIDE = "block_or_hide"
    const val LOCATION_UPDATE = "location_update"
    const val DATA_EXPORT = "data_export"
    const val APPEAL = "appeal"
    const val PUSH_REGISTER = "push_register"
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
