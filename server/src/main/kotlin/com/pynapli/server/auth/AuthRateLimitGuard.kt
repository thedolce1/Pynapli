package com.pynapli.server.auth

import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.auth.security.normalizeEmail
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

/**
 * Rate limits for the auth flows. Every check here keeps working while Redis is down, on the
 * in-process [com.pynapli.server.safety.RateLimitOutageFallback]: sign-in must not depend on it.
 */
internal class AuthRateLimitGuard(
    private val rateLimitService: RateLimitService?,
    // How a sign-in past the per-account ceiling is slowed; tests pass a recorder.
    private val slowdown: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun enforce(
        action: String,
        primarySubject: String,
        secondarySubject: String? = null,
    ) {
        val limiter = rateLimitService ?: return
        if (limiter.checkSubject(primarySubject, action, fallbackOnOutage = true).allowed == false) throw AuthException("RATE_LIMITED")
        secondarySubject
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { subject ->
                if (limiter.checkSubject("$action:$subject", action, fallbackOnOutage = true).allowed == false) {
                    throw AuthException("RATE_LIMITED")
                }
            }
    }

    /**
     * Runs before a sign-in's password check. Only failed attempts are counted (see
     * [recordLoginFailure]), and the hard limit is per account *and* client address, so someone on
     * another network can't lock the real user out by failing on purpose; guessing from one address
     * stops after [RateLimitedAction.AUTH_LOGIN_FAILURE]'s budget. Past the much higher per-account
     * ceiling (a distributed attack), every attempt is slowed down instead of refused.
     */
    suspend fun beforeLoginAttempt(
        email: String,
        clientIp: String?,
    ) {
        val limiter = rateLimitService ?: return
        val account = normalizeEmail(email)
        if (!limiter.peekSubject(loginFailureSubject(account, clientIp), RateLimitedAction.AUTH_LOGIN_FAILURE, fallbackOnOutage = true).allowed) {
            throw AuthException("RATE_LIMITED")
        }
        if (!limiter.peekSubject(account, RateLimitedAction.AUTH_LOGIN_FAILURE_ACCOUNT, fallbackOnOutage = true).allowed) {
            slowdown(LOGIN_SLOWDOWN_MILLIS)
        }
    }

    /** Spends one failed sign-in from both budgets. A successful sign-in never calls this. */
    suspend fun recordLoginFailure(
        email: String,
        clientIp: String?,
    ) {
        val limiter = rateLimitService ?: return
        val account = normalizeEmail(email)
        limiter.checkSubject(loginFailureSubject(account, clientIp), RateLimitedAction.AUTH_LOGIN_FAILURE, fallbackOnOutage = true)
        val perAccount = limiter.checkSubject(account, RateLimitedAction.AUTH_LOGIN_FAILURE_ACCOUNT, fallbackOnOutage = true)
        if (perAccount.count == perAccount.limit + 1) {
            // Logged once per window. The account is identified by a hash prefix, never the email.
            logger.warn(
                "Failed sign-ins for one account passed {} in {}s from many addresses; slowing further attempts (account {})",
                perAccount.limit,
                perAccount.windowSeconds,
                Sha256.hash(account).take(ACCOUNT_HASH_PREFIX),
            )
        }
    }

    private fun loginFailureSubject(
        account: String,
        clientIp: String?,
    ): String = "$account|${clientIp?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown-address"}"

    companion object {
        const val LOGIN_SLOWDOWN_MILLIS = 3_000L
        private const val ACCOUNT_HASH_PREFIX = 12
        private val logger = LoggerFactory.getLogger(AuthRateLimitGuard::class.java)
    }
}
