package com.pynapli.server.safety

import com.pynapli.server.dependencies.RedisCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RedisRateLimitRepository(
    private val redisCache: RedisCache
) : RateLimitRepository {
    override suspend fun increment(key: String, windowSeconds: Long): RateLimitCounter =
        withContext(Dispatchers.IO) {
            val commands = redisCache.commands
            val count = commands.incr(key)
            if (count == 1L) {
                commands.expire(key, windowSeconds)
            }
            val ttl = commands.ttl(key).takeIf { it >= 0 } ?: windowSeconds
            RateLimitCounter(count = count, ttlSeconds = ttl)
        }
}
