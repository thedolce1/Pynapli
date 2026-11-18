package com.pynapli.server.auth

import java.time.Duration

internal val AUTH_ACCESS_TOKEN_TTL: Duration = Duration.ofMinutes(15)
internal val AUTH_REFRESH_TOKEN_TTL: Duration = Duration.ofDays(30)

// How long after a rotation the refresh token it consumed may be presented again and get back the
// SAME tokens that rotation issued (the first response was lost, or two tabs raced). Nothing new is
// minted on a replay, and only the most recent previous token is honoured. Kept short: a stolen old
// token is worth the current tokens for this long after the legitimate client rotated it.
internal val AUTH_REFRESH_REPLAY_GRACE: Duration = Duration.ofSeconds(60)
internal const val AUTH_TERMS_VERSION = "2026-05-13"
internal const val AUTH_PRIVACY_VERSION = "2026-05-13"
