package com.pynapli.server.auth

import com.pynapli.server.safety.RateLimitService

internal class AuthRateLimitGuard(
    private val rateLimitService: RateLimitService?,
) {
    suspend fun enforce(
        action: String,
        primarySubject: String,
        secondarySubject: String? = null,
    ) {
        val limiter = rateLimitService ?: return
        if (limiter.checkSubject(primarySubject, action).allowed == false) throw AuthException("RATE_LIMITED")
        secondarySubject
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { subject ->
                if (limiter.checkSubject("$action:$subject", action).allowed == false) {
                    throw AuthException("RATE_LIMITED")
                }
            }
    }
}
