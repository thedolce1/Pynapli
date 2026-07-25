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
