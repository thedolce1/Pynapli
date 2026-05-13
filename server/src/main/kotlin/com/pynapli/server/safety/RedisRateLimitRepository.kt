package com.pynapli.server.safety

import com.pynapli.server.dependencies.RedisCache
import io.lettuce.core.ScriptOutputType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RedisRateLimitRepository(
    private val redisCache: RedisCache
) : RateLimitRepository {
    override suspend fun increment(key: String, windowSeconds: Long): RateLimitCounter =
        withContext(Dispatchers.IO) {
            val result = redisCache.commands.eval<List<Any>>(
                RATE_LIMIT_SCRIPT,
                ScriptOutputType.MULTI,
                arrayOf(key),
                windowSeconds.toString()
            )
            RateLimitCounter(
                count = result[0].toLongValue(),
                ttlSeconds = result[1].toLongValue().takeIf { it >= 0 } ?: windowSeconds
            )
        }

    private fun Any.toLongValue(): Long =
        when (this) {
            is Number -> toLong()
            is String -> toLong()
            else -> error("Unexpected Redis rate-limit script value: $this")
        }

    companion object {
        private val RATE_LIMIT_SCRIPT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
                return { count, tonumber(ARGV[1]) }
            end

            local ttl = redis.call('TTL', KEYS[1])
            if ttl < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
                ttl = tonumber(ARGV[1])
            end
            return { count, ttl }
        """.trimIndent()
    }
}
