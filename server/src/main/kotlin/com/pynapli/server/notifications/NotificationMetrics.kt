package com.pynapli.server.notifications

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Privacy-safe push-delivery metrics (PRODUCTION_READINESS_AND_QUALITY_SPEC: "Push send
 * success/failure metrics"). In-process, dependency-free counters keyed only by LOW-CARDINALITY,
 * NON-SENSITIVE labels — delivery outcome, notification kind, and device platform. It never records
 * recipients, actors, message bodies, payloads, tokens, or any lifestyle data, honoring the spec's
 * "Do not log" list. A snapshot can be emitted to structured logs or scraped by a future exporter.
 */
class NotificationMetrics {
    enum class Outcome { SENT, RETRIED, SKIPPED, FAILED, DEVICE_DEACTIVATED }

    private data class Key(
        val outcome: Outcome,
        val kind: String,
        val platform: String,
    )

    private val counters = ConcurrentHashMap<Key, AtomicLong>()

    /**
     * Records one delivery outcome. [kind] is a notification-kind constant (low cardinality);
     * [platform] is "android"/"ios"/"none" — never anything user-identifying.
     */
    fun record(
        outcome: Outcome,
        kind: String,
        platform: String = PLATFORM_NONE,
    ) {
        val key = Key(outcome, kind.normalizedLabel(), platform.normalizedLabel())
        counters.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
    }

    /** Immutable point-in-time snapshot of all counters, for logging or export. */
    fun snapshot(): List<NotificationMetricSample> =
        counters.entries
            .map { (key, value) ->
                NotificationMetricSample(
                    outcome = key.outcome,
                    kind = key.kind,
                    platform = key.platform,
                    count = value.get(),
                )
            }.sortedWith(compareBy({ it.outcome.name }, { it.kind }, { it.platform }))

    /** Total count for one outcome across all kinds/platforms. */
    fun totalFor(outcome: Outcome): Long = counters.entries.filter { it.key.outcome == outcome }.sumOf { it.value.get() }

    private fun String.normalizedLabel(): String = trim().lowercase().takeIf { it.matches(SAFE_LABEL) } ?: "unknown"

    companion object {
        const val PLATFORM_NONE = "none"

        // Guards against an unexpected high-cardinality or unsafe label sneaking into a metric key.
        private val SAFE_LABEL = Regex("""[a-z0-9_]{1,40}""")
    }
}

data class NotificationMetricSample(
    val outcome: NotificationMetrics.Outcome,
    val kind: String,
    val platform: String,
    val count: Long,
)
