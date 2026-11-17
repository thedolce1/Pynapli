package com.pynapli.server.testsupport

import com.pynapli.server.safety.RateLimitCounter
import com.pynapli.server.safety.RateLimitRepository

/**
 * Counts per key like Redis INCR; windows never end during a test. Set [down] to make every call
 * fail the way a lost Redis connection does.
 */
internal class CountingRateLimitRepository : RateLimitRepository {
    val counts = mutableMapOf<String, Long>()
    var down = false

    override suspend fun increment(
        key: String,
        windowSeconds: Long,
    ): RateLimitCounter {
        if (down) throw IllegalStateException("Redis unavailable")
        val next = (counts[key] ?: 0) + 1
        counts[key] = next
        return RateLimitCounter(count = next, ttlSeconds = windowSeconds)
    }

    override suspend fun current(key: String): RateLimitCounter? {
        if (down) throw IllegalStateException("Redis unavailable")
        return counts[key]?.let { RateLimitCounter(count = it, ttlSeconds = 3_600) }
    }
}
