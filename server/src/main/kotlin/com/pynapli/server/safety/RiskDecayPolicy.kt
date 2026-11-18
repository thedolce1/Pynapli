package com.pynapli.server.safety

import java.time.Duration
import java.time.Instant

/**
 * Risk score decay (TRUST_AND_SAFETY_SPEC "Recovery And Appeals"): "Normal behavior over time decays
 * score" and "Severe reasons should not decay without manual review".
 *
 * The account score is kept as two parts in `account_risk_states`: `decayable_points` (points that
 * came from minor, behavioural signals) and the rest (`score - decayable_points`), which never
 * decays. Decay is applied lazily, when the account's risk state is next read or written, and every
 * application is written to `account_risk_events` as a `decay.minor` event.
 */
object RiskDecayPolicy {
    /**
     * One decayable point is forgiven per interval: about 1 point a day, so a few rate-limit hits
     * (2 to 6 points each) clear within a week. The spec gives no rate; this number is ours.
     */
    val POINT_INTERVAL: Duration = Duration.ofDays(1)

    const val SOURCE_DECAY = "decay"
    const val REASON_DECAY = "decay.minor"

    /**
     * Sources whose points decay. An allow-list on purpose: rate-limit hits are the only signal the
     * spec treats as behavioural noise a real heavy user can trip. Every other source (reports,
     * message review, media moderation, location, device, verification, admin actions) and any
     * source added later stays non-decaying until it is classed here deliberately.
     */
    private val DECAYABLE_SOURCES = setOf(RiskService.SOURCE_RATE_LIMIT)

    fun isDecayable(source: String): Boolean = source in DECAYABLE_SOURCES

    /** How much decay is due now, and where the decay clock continues from. */
    data class Step(
        val points: Int,
        /** Null when the decayable part reaches zero: the clock restarts at the next minor point. */
        val nextAnchor: Instant?,
    )

    /**
     * The decay due for an account holding [decayablePoints] (already clamped to its score) whose
     * decay clock started at [anchor]. Null when nothing is due: no decayable points, no anchor, or
     * less than one [POINT_INTERVAL] elapsed. The anchor moves forward only by the whole intervals
     * consumed, so frequent reads never lose the partial interval in progress.
     */
    fun step(
        decayablePoints: Int,
        anchor: Instant?,
        now: Instant,
    ): Step? {
        if (decayablePoints <= 0 || anchor == null) return null
        val elapsed = Duration.between(anchor, now)
        if (elapsed.isNegative) return null
        val due = elapsed.seconds / POINT_INTERVAL.seconds
        if (due < 1) return null
        val points = minOf(decayablePoints.toLong(), due).toInt()
        val remaining = decayablePoints - points
        val nextAnchor = if (remaining == 0) null else anchor.plus(POINT_INTERVAL.multipliedBy(points.toLong()))
        return Step(points = points, nextAnchor = nextAnchor)
    }
}
