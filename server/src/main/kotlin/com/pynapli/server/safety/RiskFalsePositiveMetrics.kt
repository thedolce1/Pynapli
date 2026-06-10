package com.pynapli.server.safety

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * False-positive metrics for enforcement decisions (TRUST_AND_SAFETY_SPEC "False-
 * positive metrics exist"): counts appeal decisions by outcome so the overturn
 * rate — restored/reduced versus everything decided — is observable. In-process,
 * dependency-free counters keyed only by the validated low-cardinality outcome
 * label, never user identifiers or appeal content (mirrors NotificationMetrics;
 * a snapshot can be emitted to structured logs or scraped by a future exporter).
 */
class RiskFalsePositiveMetrics {
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    fun recordAppealDecision(outcome: String) {
        counters.computeIfAbsent(outcome.normalizedLabel()) { AtomicLong(0) }.incrementAndGet()
    }

    /** Immutable point-in-time snapshot of decision counts by outcome. */
    fun snapshot(): Map<String, Long> = counters.entries.associate { (outcome, count) -> outcome to count.get() }.toSortedMap()

    /** Enforcement decisions an appeal overturned, fully (restored) or partially (reduced). */
    fun overturnedTotal(): Long = OVERTURNED_OUTCOMES.sumOf { outcome -> counters[outcome]?.get() ?: 0 }

    fun decidedTotal(): Long = counters.values.sumOf { it.get() }

    private fun String.normalizedLabel(): String = trim().lowercase().takeIf { it.matches(SAFE_LABEL) } ?: "unknown"

    companion object {
        val OVERTURNED_OUTCOMES = setOf("restored", "reduced")

        // Guards against an unexpected high-cardinality or unsafe label sneaking
        // into a metric key.
        private val SAFE_LABEL = Regex("""[a-z0-9_]{1,40}""")
    }
}
