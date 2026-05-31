package com.pynapli.app.conversation

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal const val TYPING_SIGNAL_THROTTLE_MS = 2_500L
internal const val TYPING_INDICATOR_TTL_MS = 6_000L

@OptIn(ExperimentalTime::class)
internal fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

@OptIn(ExperimentalTime::class)
internal fun parseInstantMillis(value: String): Long? = runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
