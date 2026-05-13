package com.pynapli.server.presence

import com.pynapli.server.dependencies.RedisCache
import com.pynapli.server.profiling.profileBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.UUID

class RedisPresenceRepository(
    private val redisCache: RedisCache,
    private val clock: Clock = Clock.systemUTC()
) : PresenceRepository {
    override suspend fun heartbeat(userId: UUID): PresenceState = withContext(Dispatchers.IO) {
        profileBlocking("redis.presence.setex") {
            redisCache.commands.setex(keyFor(userId), ONLINE_TTL_SECONDS.toLong(), clock.instant().toString())
        }
        PresenceState(online = true, expiresInSeconds = ONLINE_TTL_SECONDS)
    }

    override suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = withContext(Dispatchers.IO) {
        if (userIds.isEmpty()) return@withContext emptySet()

        val uniqueUserIds = userIds.distinct()
        val values = profileBlocking("redis.presence.mget") {
            redisCache.commands.mget(*uniqueUserIds.map { keyFor(it) }.toTypedArray())
        }
        buildSet {
            values.forEachIndexed { index, value ->
                if (value.hasValue()) add(uniqueUserIds[index])
            }
        }
    }

    private fun keyFor(userId: UUID): String = "presence:user:$userId"

    companion object {
        const val ONLINE_TTL_SECONDS = 300
    }
}
