package com.pynapli.server.metinperson

/**
 * One badge's state between weekly batches (TRUST_AND_SAFETY_SPEC "Met in person"). A threshold
 * crossing, on or off, takes effect only after a random 1 to 4 further batches, chosen per subject per
 * crossing and stored; turning off also needs the count to stay below the threshold for 2 consecutive
 * batches first. A crossing that reverses before it takes effect is cancelled. So nobody, voucher or
 * subject, can tie a badge change to one confirmation or one week.
 */
internal data class BadgeTrack(
    val published: Boolean,
    // Pending crossing: the value it will take, and how many more batches until it does.
    val target: Boolean? = null,
    val remaining: Int? = null,
    // Consecutive batches below the threshold while published.
    val below: Int = 0,
    // The couple badge's link (token): what is pending, or what is published.
    val linkToken: String? = null,
) {
    val idle: Boolean get() = target == null && below == 0
}

internal object MetInPersonHysteresis {
    const val MIN_EXTRA_BATCHES = 1
    const val MAX_EXTRA_BATCHES = 4
    const val OFF_STREAK = 2

    /**
     * One batch. [qualifies] is this batch's verdict; [token] is the qualifying couple link (null for
     * the person badge). [delay] draws 1 to 4 when a crossing starts.
     */
    fun step(
        track: BadgeTrack,
        qualifies: Boolean,
        token: String? = null,
        delay: () -> Int,
    ): BadgeTrack {
        if (!track.published) {
            return when {
                !qualifies -> BadgeTrack(published = false)
                track.target == true && track.linkToken == token -> {
                    val remaining = (track.remaining ?: 1) - 1
                    if (remaining <= 0) BadgeTrack(published = true, linkToken = token) else track.copy(remaining = remaining)
                }
                else -> BadgeTrack(published = false, target = true, remaining = delay(), linkToken = token)
            }
        }
        if (qualifies && token == track.linkToken) return BadgeTrack(published = true, linkToken = track.linkToken)
        val below = track.below + 1
        return when {
            track.target == false -> {
                val remaining = (track.remaining ?: 1) - 1
                if (remaining <= 0) BadgeTrack(published = false) else track.copy(remaining = remaining, below = below)
            }
            below >= OFF_STREAK -> track.copy(target = false, remaining = delay(), below = below)
            else -> track.copy(below = below)
        }
    }
}
