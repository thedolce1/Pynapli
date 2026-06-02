package com.pynapli.server.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationMetricsTest {
    @Test
    fun recordsCountsKeyedByOutcomeKindAndPlatform() {
        val metrics = NotificationMetrics()
        metrics.record(NotificationMetrics.Outcome.SENT, NotificationKind.MESSAGE, "android")
        metrics.record(NotificationMetrics.Outcome.SENT, NotificationKind.MESSAGE, "android")
        metrics.record(NotificationMetrics.Outcome.SENT, NotificationKind.MATCH, "ios")
        metrics.record(NotificationMetrics.Outcome.FAILED, NotificationKind.MESSAGE, "android")

        assertEquals(3, metrics.totalFor(NotificationMetrics.Outcome.SENT))
        assertEquals(1, metrics.totalFor(NotificationMetrics.Outcome.FAILED))

        val messageAndroidSent =
            metrics.snapshot().single {
                it.outcome == NotificationMetrics.Outcome.SENT && it.kind == "message" && it.platform == "android"
            }
        assertEquals(2, messageAndroidSent.count)
    }

    @Test
    fun defaultsPlatformToNoneAndNormalizesUnsafeLabels() {
        val metrics = NotificationMetrics()
        metrics.record(NotificationMetrics.Outcome.SKIPPED, NotificationKind.SYSTEM_SAFETY)
        // An unexpected high-cardinality / unsafe label is coerced to "unknown" so a metric key can
        // never leak something user-identifying.
        metrics.record(NotificationMetrics.Outcome.RETRIED, "weird kind with spaces", "ANDROID-???")

        val skipped = metrics.snapshot().single { it.outcome == NotificationMetrics.Outcome.SKIPPED }
        assertEquals("none", skipped.platform)
        assertEquals("system_safety", skipped.kind)

        val retried = metrics.snapshot().single { it.outcome == NotificationMetrics.Outcome.RETRIED }
        assertEquals("unknown", retried.kind)
        assertEquals("unknown", retried.platform)
    }

    @Test
    fun snapshotIsEmptyWhenNothingRecorded() {
        assertTrue(NotificationMetrics().snapshot().isEmpty())
    }
}
