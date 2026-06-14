package com.pynapli.server.tonight

import java.time.Duration

internal const val TONIGHT_MODE_SOLO = "solo"
internal const val TONIGHT_MODE_TOGETHER = "together"
internal const val TONIGHT_ROLE_OWNER = "owner"
internal const val TONIGHT_ROLE_PARTNER = "partner"
internal const val TONIGHT_MAX_NOTE_LENGTH = 100
internal const val TONIGHT_DEFAULT_DURATION_HOURS = 2
internal const val TONIGHT_MIN_DURATION_HOURS = 1
internal const val TONIGHT_MAX_DURATION_HOURS = 24
internal const val TONIGHT_FREE_WEEKLY_LIMIT = 3
internal val TONIGHT_WEEKLY_WINDOW: Duration = Duration.ofDays(7)

// PLAYING_TONIGHT_SPEC: "Recommended reminder: 15 minutes before expiration."
internal val TONIGHT_REMINDER_LEAD_WINDOW: Duration = Duration.ofMinutes(15)

// PLAYING_TONIGHT_SPEC: "pause linked-together session briefly, then end if not restored."
internal val TONIGHT_PAUSE_GRACE: Duration = Duration.ofMinutes(5)

internal val TONIGHT_VALID_MODES = setOf(TONIGHT_MODE_SOLO, TONIGHT_MODE_TOGETHER)
internal val TONIGHT_VALID_HOST_MODES = setOf("hosting", "traveling", "public_meet_first")
internal val TONIGHT_VALID_INTENTS = setOf("solo", "together", "social", "event")
internal val TONIGHT_SOLO_INTENTS = setOf("solo", "social", "event")
internal val TONIGHT_TOGETHER_INTENTS = setOf("together", "social", "event")
