package com.pynapli.server.safety

interface RateLimitRepository {
    suspend fun increment(
        key: String,
        windowSeconds: Long,
    ): RateLimitCounter

    /** The counter for [key] without changing it; null when it has none (or its window has ended). */
    suspend fun current(key: String): RateLimitCounter?
}

data class RateLimitCounter(
    val count: Long,
    val ttlSeconds: Long,
)
