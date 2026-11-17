package com.pynapli.server.safety

import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * What sign-in, sign-up, refresh and password reset are limited by while Redis is unreachable
 * (RateLimitService with `fallbackOnOutage`). Without it those calls failed outright whenever Redis
 * did. Counters live in this replica only, so the effective limit is per replica during an outage,
 * and they are dropped once Redis answers again.
 *
 * Bounded: at most [maxKeys] counters, least recently used evicted first, so a flood of distinct
 * subjects can't grow the heap. Logs once when it takes over and once when Redis is back;
 * [active] and [fallbackChecks] are exported on /metrics.
 */
class RateLimitOutageFallback(
    maxKeys: Int = DEFAULT_MAX_KEYS,
    clock: Clock = Clock.systemUTC(),
) {
    val repository = InProcessRateLimitRepository(maxKeys, clock)

    @Volatile
    var active: Boolean = false
        private set

    private val checks = AtomicLong()

    /** Rate-limit checks answered here instead of by Redis, since start. */
    val fallbackChecks: Long get() = checks.get()

    internal fun failedOver(error: Throwable) {
        checks.incrementAndGet()
        if (!active) {
            active = true
            logger.warn("Redis rate limiting unavailable ({}); using in-process limits on this replica", error.javaClass.simpleName)
        }
    }

    internal fun recovered() {
        if (active) {
            active = false
            repository.clear()
            logger.info("Redis rate limiting is back; in-process fallback limits dropped")
        }
    }

    companion object {
        const val DEFAULT_MAX_KEYS = 50_000
        private val logger = LoggerFactory.getLogger(RateLimitOutageFallback::class.java)
    }
}

/** Fixed-window counters in memory, least recently used evicted past [maxKeys]. */
class InProcessRateLimitRepository(
    private val maxKeys: Int,
    private val clock: Clock = Clock.systemUTC(),
) : RateLimitRepository {
    private class Window(
        var count: Long,
        val endsAtMillis: Long,
    )

    private val windows =
        object : LinkedHashMap<String, Window>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Window>?): Boolean = size > maxKeys
        }

    override suspend fun increment(
        key: String,
        windowSeconds: Long,
    ): RateLimitCounter {
        val now = clock.millis()
        synchronized(windows) {
            val window =
                windows[key]?.takeIf { it.endsAtMillis > now }
                    ?: Window(count = 0, endsAtMillis = now + windowSeconds * 1_000).also { windows[key] = it }
            window.count += 1
            return RateLimitCounter(count = window.count, ttlSeconds = window.ttlSeconds(now))
        }
    }

    override suspend fun current(key: String): RateLimitCounter? {
        val now = clock.millis()
        synchronized(windows) {
            val window = windows[key]?.takeIf { it.endsAtMillis > now } ?: return null
            return RateLimitCounter(count = window.count, ttlSeconds = window.ttlSeconds(now))
        }
    }

    internal fun size(): Int = synchronized(windows) { windows.size }

    internal fun clear() = synchronized(windows) { windows.clear() }

    private fun Window.ttlSeconds(now: Long): Long = ((endsAtMillis - now + 999) / 1_000).coerceAtLeast(1)

    private companion object {
        const val INITIAL_CAPACITY = 1_024
        const val LOAD_FACTOR = 0.75f
    }
}
