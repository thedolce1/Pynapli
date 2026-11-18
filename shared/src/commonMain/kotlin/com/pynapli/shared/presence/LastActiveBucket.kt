package com.pynapli.shared.presence

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Coarse "last seen" buckets (GRID_IMPLEMENTATION_SPEC "Presence States"). The server picks the bucket
 * and sends only its key, never a timestamp; clients turn the key into a label. Shown on profile
 * detail only, never on the Grid card face (the card keeps just the online dot).
 */
enum class LastActiveBucket(
    val key: String,
    val label: String,
) {
    OnlineNow("online_now", "Online now"),
    Recent("active_15m", "Active 15 min ago"),
    Today("active_today", "Active today"),
    ThisWeek("active_week", "Active this week"),
    ;

    companion object {
        private val RECENT_WINDOW: Duration = 15.minutes
        private val TODAY_WINDOW: Duration = 24.hours
        private val WEEK_WINDOW: Duration = 7.days

        fun fromKey(key: String?): LastActiveBucket? = entries.firstOrNull { it.key == key }

        /** Display label for a wire key, or null when the key is missing or unknown. */
        fun labelFor(key: String?): String? = fromKey(key)?.label

        /**
         * The bucket for someone last active [sinceLastActive] ago, or null past a week. [online] wins
         * over any timestamp so the label never contradicts the online dot.
         */
        fun bucketFor(
            online: Boolean,
            sinceLastActive: Duration?,
        ): LastActiveBucket? =
            when {
                online -> OnlineNow
                sinceLastActive == null -> null
                sinceLastActive <= RECENT_WINDOW -> Recent
                sinceLastActive <= TODAY_WINDOW -> Today
                sinceLastActive <= WEEK_WINDOW -> ThisWeek
                else -> null
            }
    }
}
