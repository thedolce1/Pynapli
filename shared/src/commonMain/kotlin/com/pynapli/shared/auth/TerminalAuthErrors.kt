package com.pynapli.shared.auth

/**
 * Auth error codes that mean the session is permanently done — not merely failing right now — so
 * clearing local tokens and returning to sign-in is the correct response.
 *
 * Network and 5xx codes are deliberately absent: those are transient, and treating them as terminal
 * would sign a user out on a passing connectivity blip. Shared by [AuthRepository] (launch-time
 * validation) and [StoredSessionRefresher] (mid-session refresh) so both judge "dead" the same way.
 */
internal val terminalAuthErrorCodes: Set<String> =
    setOf(
        "UNAUTHENTICATED",
        "INVALID_REFRESH_TOKEN",
        "ACCOUNT_NOT_ACTIVE",
        "HTTP_401",
    )

/**
 * The subset of [terminalAuthErrorCodes] that a 401 response maps to. Every shell routes a 401 on an
 * authenticated call through `StoredSessionRefresher` first, so by the time a caller sees one of these
 * the refresh has either cleared a dead session or failed transiently and left a live one in place.
 */
internal val unauthorizedCodes: Set<String> = setOf("UNAUTHENTICATED", "HTTP_401")
