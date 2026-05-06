package com.pynapli.server.safety

interface RateLimitRepository {
    suspend fun increment(key: String, windowSeconds: Long): RateLimitCounter
}

data class RateLimitCounter(
    val count: Long,
    val ttlSeconds: Long
)
