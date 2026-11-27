package com.pynapli.server.presence

import java.time.Duration

/**
 * The Grid card's coarse "active Xm ago" key (plan G11; API_CONTRACT_SPEC "Grid card activity").
 * Only the key leaves the server, never a timestamp. Each key covers the window up to its bound,
 * after the previous one: "15m" is 15 minutes or less, "1h" more than 15 minutes up to an hour,
 * "3h" up to 3 hours, "today" up to 24 hours, "week" up to 7 days. Older, never seen, or hidden by
 * the member's privacy settings (incognito) is null. Online wins, so the key never contradicts the
 * dot; a hidden member keeps today's online-dot rules but gets no key at all.
 */
object GridActiveAgo {
    const val ONLINE = "online"
    const val WITHIN_15_MINUTES = "15m"
    const val WITHIN_1_HOUR = "1h"
    const val WITHIN_3_HOURS = "3h"
    const val TODAY = "today"
    const val THIS_WEEK = "week"

    val keys: List<String> = listOf(ONLINE, WITHIN_15_MINUTES, WITHIN_1_HOUR, WITHIN_3_HOURS, TODAY, THIS_WEEK)

    fun keyFor(
        online: Boolean,
        activityHidden: Boolean,
        sinceLastActive: Duration?,
    ): String? =
        when {
            activityHidden -> null
            online -> ONLINE
            sinceLastActive == null -> null
            sinceLastActive <= Duration.ofMinutes(15) -> WITHIN_15_MINUTES
            sinceLastActive <= Duration.ofHours(1) -> WITHIN_1_HOUR
            sinceLastActive <= Duration.ofHours(3) -> WITHIN_3_HOURS
            sinceLastActive <= Duration.ofHours(24) -> TODAY
            sinceLastActive <= Duration.ofDays(7) -> THIS_WEEK
            else -> null
        }
}
