package com.pynapli.server.safety

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RiskDecayPolicyTest {
    private val anchor = Instant.parse("2026-11-01T00:00:00Z")

    @Test
    fun onlyRateLimitPointsDecay() {
        assertTrue(RiskDecayPolicy.isDecayable(RiskService.SOURCE_RATE_LIMIT))
        listOf(
            RiskService.SOURCE_REPORT,
            RiskService.SOURCE_MESSAGE_REVIEW,
            RiskService.SOURCE_MEDIA_MODERATION,
            RiskService.SOURCE_LOCATION,
            RiskService.SOURCE_VERIFICATION,
            RiskService.SOURCE_DEVICE,
            "admin_review",
            "admin_enforcement",
            RiskDecayPolicy.SOURCE_DECAY,
            "some_future_source",
        ).forEach { assertFalse(RiskDecayPolicy.isDecayable(it), it) }
    }

    @Test
    fun nothingIsDueBeforeAWholeInterval() {
        assertNull(RiskDecayPolicy.step(10, anchor, anchor.plus(Duration.ofHours(23))))
        assertNull(RiskDecayPolicy.step(0, anchor, anchor.plus(Duration.ofDays(30))))
        assertNull(RiskDecayPolicy.step(10, null, anchor.plus(Duration.ofDays(30))))
        assertNull(RiskDecayPolicy.step(10, anchor, anchor.minus(Duration.ofDays(1))))
    }

    @Test
    fun theAnchorAdvancesOnlyByWholeIntervalsConsumed() {
        val step = RiskDecayPolicy.step(10, anchor, anchor.plus(Duration.ofHours(60)))
        assertEquals(2, step?.points)
        // The 12 hours in progress are kept, so frequent reads never stall decay.
        assertEquals(anchor.plus(Duration.ofDays(2)), step?.nextAnchor)
    }

    @Test
    fun decayStopsAtTheDecayablePartAndClearsTheClock() {
        val step = RiskDecayPolicy.step(3, anchor, anchor.plus(Duration.ofDays(40)))
        assertEquals(3, step?.points)
        assertNull(step?.nextAnchor)
    }
}
