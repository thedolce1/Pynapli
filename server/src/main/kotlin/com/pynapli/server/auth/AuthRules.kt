package com.pynapli.server.auth

import java.time.Duration

internal val AUTH_ACCESS_TOKEN_TTL: Duration = Duration.ofMinutes(15)
internal val AUTH_REFRESH_TOKEN_TTL: Duration = Duration.ofDays(30)
internal const val AUTH_TERMS_VERSION = "2026-05-13"
internal const val AUTH_PRIVACY_VERSION = "2026-05-13"
