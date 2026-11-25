package com.pynapli.server.auth

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall

private const val BEARER_PREFIX = "Bearer "

/**
 * The token from an `Authorization: Bearer <token>` header value, or null when the header is absent,
 * uses another scheme, or carries no token. The scheme is matched case-insensitively (RFC 7235), and
 * the token is everything after it: the old per-route copies matched the scheme ignoring case but then
 * split on the exact string "Bearer ", so `bearer abc` yielded the whole header as the "token".
 */
internal fun parseBearerToken(headerValue: String?): String? {
    if (headerValue == null || !headerValue.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) {
        return null
    }
    return headerValue.substring(BEARER_PREFIX.length).trim().takeIf { it.isNotEmpty() }
}

/** The request's bearer token, or null when there isn't a well-formed one. */
fun ApplicationCall.bearerTokenOrNull(): String? = parseBearerToken(request.headers[HttpHeaders.Authorization])

/** The request's bearer token; throws `UNAUTHENTICATED` (401 via StatusPages) when missing or malformed. */
fun ApplicationCall.requiredBearerToken(): String = bearerTokenOrNull() ?: throw AuthException("UNAUTHENTICATED")

/** Authenticates the request's bearer token and returns the signed-in user, or throws `UNAUTHENTICATED`. */
suspend fun ApplicationCall.authenticatedUser(authService: AuthService): UserRecord = authService.authenticate(requiredBearerToken())

/**
 * [authenticatedUser] that does not count as activity (no presence refresh): for routes that are not
 * the member using the app, such as push-device registration (also sent from the background) and
 * unregistration (sent at logout), session and device management, account export and deletion.
 */
suspend fun ApplicationCall.authenticatedUserWithoutActivity(authService: AuthService): UserRecord = authService.authenticateWithoutActivity(requiredBearerToken())
