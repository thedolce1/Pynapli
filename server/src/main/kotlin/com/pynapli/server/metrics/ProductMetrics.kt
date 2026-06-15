package com.pynapli.server.metrics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Authoritative product-event KPIs counted server-side from real state changes
 * (PRODUCTION_READINESS_AND_QUALITY_SPEC / ANALYTICS_AND_METRICS_SPEC: "server product-event
 * metrics for authoritative KPIs"). In-process, dependency-free counters keyed only by a
 * LOW-CARDINALITY event label — never users, emails, tokens, message bodies, locations, or any
 * lifestyle data, honoring the spec's "Do not log" list. A snapshot can be scraped by the admin
 * metrics endpoint or emitted to a future exporter.
 *
 * Counts are authoritative because each is recorded at the server chokepoint where the state change
 * actually succeeded — not from client-reported analytics, which the spec forbids trusting for KPIs.
 */
class ProductMetrics {
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    fun record(event: String) {
        val label = event.normalizedLabel()
        counters.computeIfAbsent(label) { AtomicLong(0) }.incrementAndGet()
    }

    fun countFor(event: String): Long = counters[event.normalizedLabel()]?.get() ?: 0

    /** Immutable point-in-time snapshot of all counters, for logging or export. */
    fun snapshot(): List<ProductMetricSample> =
        counters.entries
            .map { (event, value) -> ProductMetricSample(event = event, count = value.get()) }
            .sortedBy { it.event }

    private fun String.normalizedLabel(): String = trim().lowercase().takeIf { it.matches(SAFE_LABEL) } ?: "unknown"

    companion object {
        // Low-cardinality authoritative product events.
        const val SIGNUP = "signup"
        const val LOGIN = "login"
        const val MESSAGE_SENT = "message_sent"
        const val MATCH_CREATED = "match_created"
        const val PURCHASE_VERIFIED = "purchase_verified"

        // Guards against an unexpected high-cardinality or unsafe label sneaking into a metric key.
        private val SAFE_LABEL = Regex("""[a-z0-9_]{1,40}""")
    }
}

data class ProductMetricSample(
    val event: String,
    val count: Long,
)
