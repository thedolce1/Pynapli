package com.pynapli.server.metrics

import kotlin.test.Test
import kotlin.test.assertEquals

class ProductMetricsTest {
    @Test
    fun countsEventsByLabelAndSnapshotsSorted() {
        val metrics = ProductMetrics()
        repeat(3) { metrics.record(ProductMetrics.SIGNUP) }
        metrics.record(ProductMetrics.LOGIN)
        metrics.record(ProductMetrics.MESSAGE_SENT)

        assertEquals(3, metrics.countFor(ProductMetrics.SIGNUP))
        assertEquals(1, metrics.countFor(ProductMetrics.LOGIN))
        assertEquals(0, metrics.countFor("never_recorded"))

        assertEquals(
            listOf("login", "message_sent", "signup"),
            metrics.snapshot().map { it.event },
        )
        assertEquals(3, metrics.snapshot().single { it.event == "signup" }.count)
    }

    @Test
    fun normalizesLabelsAndFoldsUnsafeOnesToUnknown() {
        val metrics = ProductMetrics()
        metrics.record("  SignUp  ") // trims + lowercases onto the same bucket as SIGNUP
        metrics.record(ProductMetrics.SIGNUP)
        metrics.record("bad label!") // unsafe characters -> "unknown"

        assertEquals(2, metrics.countFor(ProductMetrics.SIGNUP))
        assertEquals(1, metrics.countFor("unknown"))
    }
}
