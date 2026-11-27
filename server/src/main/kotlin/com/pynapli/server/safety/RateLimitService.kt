package com.pynapli.server.safety

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class RateLimitService(
    private val repository: RateLimitRepository,
    private val riskSignalPublisher: RateLimitRiskSignalPublisher? = null,
    /** Answers checks made with `fallbackOnOutage` while [repository] (Redis) is unreachable. */
    val outageFallback: RateLimitOutageFallback = RateLimitOutageFallback(),
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

    /**
     * Counts one [action] for [subject]. With [fallbackOnOutage], a Redis failure is answered by the
     * in-process [outageFallback] instead of throwing: for flows that must keep working (sign-in).
     */
    suspend fun checkSubject(
        subject: String,
        action: String,
        fallbackOnOutage: Boolean = false,
    ): RateLimitDecision {
        val policy = POLICIES[action] ?: throw SafetyException("INVALID_RATE_LIMIT_ACTION")
        val counter =
            withOutageFallback(fallbackOnOutage) { store ->
                store.increment(key = policy.subjectKeyFor(subject), windowSeconds = policy.windowSeconds)
            }
        return RateLimitDecision(
            allowed = counter.count <= policy.limit,
            action = policy.action,
            count = counter.count,
            limit = policy.limit,
            ttlSeconds = counter.ttlSeconds,
            windowSeconds = policy.windowSeconds,
        )
    }

    /**
     * Where [subject] stands against [action]'s limit without counting anything: allowed while the
     * counter is below the limit, so one more counted attempt would still fit. Lets a caller check a
     * budget up front and spend it only when the attempt turns out to count (a failed sign-in).
     */
    suspend fun peekSubject(
        subject: String,
        action: String,
        fallbackOnOutage: Boolean = false,
    ): RateLimitDecision {
        val policy = POLICIES[action] ?: throw SafetyException("INVALID_RATE_LIMIT_ACTION")
        val counter = withOutageFallback(fallbackOnOutage) { store -> store.current(policy.subjectKeyFor(subject)) }
        val count = counter?.count ?: 0
        return RateLimitDecision(
            allowed = count < policy.limit,
            action = policy.action,
            count = count,
            limit = policy.limit,
            ttlSeconds = counter?.ttlSeconds ?: 0,
            windowSeconds = policy.windowSeconds,
        )
    }

    private suspend fun <T> withOutageFallback(
        enabled: Boolean,
        block: suspend (RateLimitRepository) -> T,
    ): T {
        if (!enabled) return block(repository)
        return try {
            block(repository).also { outageFallback.recovered() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            outageFallback.failedOver(error)
            block(outageFallback.repository)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RateLimitService::class.java)

        private val POLICIES =
            listOf(
                // Sign-up (EmailSignupService): per address + client IP, and per device.
                RateLimitPolicy(action = RateLimitedAction.AUTH_SIGNUP, limit = 10, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.AUTH_SIGNUP_RESEND, limit = 10, windowSeconds = ONE_HOUR_SECONDS),
                // Guessing a 6-digit sign-up code: each code also dies after 5 wrong tries.
                RateLimitPolicy(action = RateLimitedAction.AUTH_SIGNUP_VERIFY, limit = 15, windowSeconds = ONE_HOUR_SECONDS),
                // Silent caps on sign-up emails (code or "already registered" notice); the response never
                // changes when they trip, the email just isn't sent. Tight per address + client IP, so one
                // network can't starve the owner's own sign-up; looser per address family (+tags folded)
                // to cap a flood from many networks. EmailSignupService.ADDRESS_DAILY_EMAIL_CAP mirrors it.
                RateLimitPolicy(action = RateLimitedAction.AUTH_SIGNUP_EMAIL_HOURLY, limit = 3, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.AUTH_SIGNUP_EMAIL_DAILY, limit = 6, windowSeconds = ONE_DAY_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.AUTH_SIGNUP_EMAIL_ADDRESS_DAILY, limit = 20, windowSeconds = ONE_DAY_SECONDS),
                // Local-dev sign-up shortcut (loopback only): room for a full demo seed, still bounded.
                RateLimitPolicy(action = RateLimitedAction.AUTH_DEV_SIGNUP, limit = 2_000, windowSeconds = ONE_HOUR_SECONDS),
                // Failed sign-ins only (AuthRateLimitGuard). Per account + client IP: tight, so guessing
                // from one address stops fast, while someone elsewhere can't lock the real user out.
                RateLimitPolicy(action = RateLimitedAction.AUTH_LOGIN_FAILURE, limit = 10, windowSeconds = ONE_HOUR_SECONDS),
                // Per account from every address: past this, attempts are slowed, never refused.
                RateLimitPolicy(action = RateLimitedAction.AUTH_LOGIN_FAILURE_ACCOUNT, limit = 100, windowSeconds = ONE_HOUR_SECONDS),
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
                // Grid pages reaching into the Section 3 locked window (paywall tiles past the tier depth),
                // counted on top of GRID_QUERY. A whole Free window is 9 pages of 60 (Social 7 of 90), so 60
                // an hour is several full scrolls with refreshes, and caps scripted re-ranking of the window.
                RateLimitPolicy(action = RateLimitedAction.GRID_LOCKED_PAGE, limit = 60, windowSeconds = ONE_HOUR_SECONDS),
                // The filter sheet's "Show N people" count (plan G2-4): asked debounced while editing, and
                // answered from a short per-viewer cache, so a few hundred an hour is generous.
                RateLimitPolicy(action = RateLimitedAction.GRID_COUNT, limit = 360, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.MEDIA_UPLOAD, limit = 120, windowSeconds = ONE_HOUR_SECONDS),
                // Type-ahead: every debounced keystroke is a query, so the budget is generous.
                RateLimitPolicy(action = RateLimitedAction.TRAVEL_SEARCH, limit = 600, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.AGE_VERIFICATION_START, limit = 5, windowSeconds = ONE_HOUR_SECONDS),
                // Selfie verification: a new pose each challenge, so cap re-rolling for an easy pose.
                RateLimitPolicy(action = RateLimitedAction.PHOTO_VERIFICATION_CHALLENGE, limit = 10, windowSeconds = ONE_HOUR_SECONDS),
                // Each submission costs a reviewer's time; a handful a day covers honest retries.
                RateLimitPolicy(action = RateLimitedAction.PHOTO_VERIFICATION_SUBMIT, limit = 5, windowSeconds = ONE_DAY_SECONDS),
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
                RateLimitPolicy(action = RateLimitedAction.AUTH_SIGNUP_VERIFY_IP, limit = 60, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.AUTH_PASSWORD_RESET_IP, limit = 30, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.ACCOUNT_APPEAL_IP, limit = 20, windowSeconds = ONE_HOUR_SECONDS),
                // Continue with Google / Apple. Nonces per client IP (one per attempt, a few retries);
                // token routes per client IP; and, once a token verified, per provider identity.
                RateLimitPolicy(action = RateLimitedAction.AUTH_SOCIAL_NONCE_IP, limit = 120, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.AUTH_SOCIAL_IP, limit = 100, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.AUTH_SOCIAL_SIGN_IN, limit = 30, windowSeconds = ONE_HOUR_SECONDS),
                // Linking/unlinking a sign-in method, per account.
                RateLimitPolicy(action = RateLimitedAction.SOCIAL_IDENTITY_CHANGE, limit = 20, windowSeconds = ONE_HOUR_SECONDS),
                // Per user, far above real use: a fast chatter sends a few messages a minute.
                RateLimitPolicy(action = RateLimitedAction.MESSAGE_SEND, limit = 600, windowSeconds = ONE_HOUR_SECONDS),
                // Per sender per conversation (text + media): three a minute sustained for an hour.
                RateLimitPolicy(action = RateLimitedAction.MESSAGE_SEND_CONVERSATION, limit = 180, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.THREAD_CREATE, limit = 100, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.TYPING, limit = 2_400, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.PROFILE_LINK_INVITE, limit = 20, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.SESSION_CREATE, limit = 30, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.BLOCK_OR_HIDE, limit = 120, windowSeconds = ONE_HOUR_SECONDS),
                // Starring and un-starring: far above real use, low enough that toggling can't hammer the DB.
                RateLimitPolicy(action = RateLimitedAction.FAVORITE_UPDATE, limit = 300, windowSeconds = ONE_HOUR_SECONDS),
                // "Hide from people you know": adds per member per day (ContactBlockService.MAX_ADDS_PER_DAY).
                // Counts every add, member or not, so the budget never depends on membership.
                RateLimitPolicy(action = RateLimitedAction.CONTACT_BLOCK_ADD, limit = 100, windowSeconds = ONE_DAY_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.LOCATION_UPDATE, limit = 360, windowSeconds = ONE_HOUR_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.DATA_EXPORT, limit = 5, windowSeconds = ONE_DAY_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.APPEAL, limit = 10, windowSeconds = ONE_DAY_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.PUSH_REGISTER, limit = 60, windowSeconds = ONE_HOUR_SECONDS),
                // A private-album fetch per owner id; walking ids would enumerate who has albums.
                RateLimitPolicy(action = RateLimitedAction.PRIVATE_ALBUM_FETCH, limit = 300, windowSeconds = ONE_HOUR_SECONDS),
                // Opening someone's profile, per user: browsing opens a few dozen an hour; walking ids is a crawl.
                RateLimitPolicy(action = RateLimitedAction.PROFILE_FETCH, limit = 300, windowSeconds = ONE_HOUR_SECONDS),
                // "Met in person" menu fetches and confirm/take back, per user: a menu opens a few times
                // an hour; a script walking conversation or person ids is a crawl.
                RateLimitPolicy(action = RateLimitedAction.MET_IN_PERSON, limit = 240, windowSeconds = ONE_HOUR_SECONDS),
                // Admin per-person lookups per client IP: a moderator works a queue, not a crawl.
                RateLimitPolicy(action = RateLimitedAction.ADMIN_PERSON_LOOKUP, limit = 300, windowSeconds = ONE_HOUR_SECONDS),
                // Global abuse ceilings (RequestRateLimitPlugin), per minute so a burst is caught fast.
                // Per user: ten requests a second, sustained, is a script. Per client IP: room for a
                // venue's shared Wi-Fi. Without a session (sign-in, sign-up, reset): much lower.
                RateLimitPolicy(action = RateLimitedAction.GLOBAL_USER, limit = 600, windowSeconds = ONE_MINUTE_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.GLOBAL_IP, limit = 1_200, windowSeconds = ONE_MINUTE_SECONDS),
                RateLimitPolicy(action = RateLimitedAction.GLOBAL_ANONYMOUS_IP, limit = 120, windowSeconds = ONE_MINUTE_SECONDS),
            ).associateBy { it.action }

        private const val ONE_MINUTE_SECONDS = 60L

        private const val ONE_HOUR_SECONDS = 3_600L
        private const val ONE_DAY_SECONDS = 86_400L
        private const val NUDGE_COOLDOWN_SECONDS = 60L
    }
}

object RateLimitedAction {
    const val AUTH_SIGNUP = "auth_signup"
    const val AUTH_SIGNUP_RESEND = "auth_signup_resend"
    const val AUTH_SIGNUP_VERIFY = "auth_signup_verify"
    const val AUTH_SIGNUP_EMAIL_HOURLY = "auth_signup_email_hourly"
    const val AUTH_SIGNUP_EMAIL_DAILY = "auth_signup_email_daily"
    const val AUTH_SIGNUP_EMAIL_ADDRESS_DAILY = "auth_signup_email_address_daily"
    const val AUTH_DEV_SIGNUP = "auth_dev_signup"
    const val AUTH_LOGIN_FAILURE = "auth_login_failure"
    const val AUTH_LOGIN_FAILURE_ACCOUNT = "auth_login_failure_account"
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
    const val GRID_LOCKED_PAGE = "grid_locked_page"
    const val GRID_COUNT = "grid_count"
    const val MEDIA_UPLOAD = "media_upload"
    const val TRAVEL_SEARCH = "travel_search"
    const val AGE_VERIFICATION_START = "age_verification_start"
    const val PHOTO_VERIFICATION_CHALLENGE = "photo_verification_challenge"
    const val PHOTO_VERIFICATION_SUBMIT = "photo_verification_submit"
    const val STORE_PURCHASE = "store_purchase"
    const val BOOST_ACTIVATE = "boost_activate"
    const val PSYNC_NUDGE = "psync_nudge"
    const val PROFILE_UPDATE = "profile_update"
    const val AUTH_LOGIN_IP = "auth_login_ip"
    const val AUTH_SIGNUP_IP = "auth_signup_ip"
    const val AUTH_SIGNUP_VERIFY_IP = "auth_signup_verify_ip"
    const val AUTH_PASSWORD_RESET_IP = "auth_password_reset_ip"
    const val ACCOUNT_APPEAL_IP = "account_appeal_ip"
    const val AUTH_SOCIAL_NONCE_IP = "auth_social_nonce_ip"
    const val AUTH_SOCIAL_IP = "auth_social_ip"
    const val AUTH_SOCIAL_SIGN_IN = "auth_social_sign_in"
    const val SOCIAL_IDENTITY_CHANGE = "social_identity_change"
    const val MESSAGE_SEND = "message_send"
    const val MESSAGE_SEND_CONVERSATION = "message_send_conversation"
    const val PROFILE_FETCH = "profile_fetch"
    const val MET_IN_PERSON = "met_in_person"
    const val THREAD_CREATE = "thread_create"
    const val TYPING = "typing"
    const val PROFILE_LINK_INVITE = "profile_link_invite"
    const val SESSION_CREATE = "session_create"
    const val BLOCK_OR_HIDE = "block_or_hide"
    const val FAVORITE_UPDATE = "favorite_update"
    const val CONTACT_BLOCK_ADD = "contact_block_add"
    const val LOCATION_UPDATE = "location_update"
    const val DATA_EXPORT = "data_export"
    const val APPEAL = "appeal"
    const val PUSH_REGISTER = "push_register"
    const val PRIVATE_ALBUM_FETCH = "private_album_fetch"
    const val ADMIN_PERSON_LOOKUP = "admin_person_lookup"
    const val GLOBAL_USER = "global_user"
    const val GLOBAL_IP = "global_ip"
    const val GLOBAL_ANONYMOUS_IP = "global_anonymous_ip"
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
