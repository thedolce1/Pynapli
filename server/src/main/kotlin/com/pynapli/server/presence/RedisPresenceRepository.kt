package com.pynapli.server.presence

import com.pynapli.server.dependencies.RedisCache
import com.pynapli.server.profiling.profileBlocking
import io.lettuce.core.SetArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.UUID

class RedisPresenceRepository(
    private val redisCache: RedisCache,
    private val clock: Clock = Clock.systemUTC(),
) : PresenceRepository {
    override suspend fun heartbeat(userId: UUID): PresenceState =
        withContext(Dispatchers.IO) {
            // One atomic SET ... EX ... GET (Redis 6.2+): refreshes the TTL and returns the previous
            // value, so a missing previous key means this person was offline until now.
            val previous =
                profileBlocking("redis.presence.setGet") {
                    redisCache.commands.setGet(
                        keyFor(userId),
                        clock.instant().toString(),
                        SetArgs.Builder.ex(ONLINE_TTL_SECONDS.toLong()),
                    )
                }
            PresenceState(online = true, expiresInSeconds = ONLINE_TTL_SECONDS, cameOnline = previous == null)
        }

    override suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> =
        withContext(Dispatchers.IO) {
            if (userIds.isEmpty()) return@withContext emptySet()

            val uniqueUserIds = userIds.distinct()
            val values =
                profileBlocking("redis.presence.mget") {
                    redisCache.commands.mget(*uniqueUserIds.map { keyFor(it) }.toTypedArray())
                }
            buildSet {
                values.forEachIndexed { index, value ->
                    if (value.hasValue()) add(uniqueUserIds[index])
                }
            }
        }

    override suspend fun clear(userId: UUID) {
        withContext(Dispatchers.IO) {
            profileBlocking("redis.presence.del") { redisCache.commands.del(keyFor(userId)) }
        }
    }

    override suspend fun markPresenceEnded(
        userId: UUID,
        at: java.time.Instant,
    ) {
        withContext(Dispatchers.IO) {
            profileBlocking("redis.presence.endedSet") {
                redisCache.commands.setex(endedKeyFor(userId), ENDED_TOMBSTONE_TTL_SECONDS, at.toString())
            }
        }
    }

    override suspend fun presenceEndedAt(userId: UUID): java.time.Instant? =
        withContext(Dispatchers.IO) {
            profileBlocking("redis.presence.endedGet") { redisCache.commands.get(endedKeyFor(userId)) }
                ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        }

    private fun keyFor(userId: UUID): String = "presence:user:$userId"

    private fun endedKeyFor(userId: UUID): String = "presence:ended:$userId"

    companion object {
        /**
         * The green dot holds this long after the last authenticated request, open-socket re-auth
         * tick or heartbeat (GRID_IMPLEMENTATION_SPEC "Presence States"; plan G2: 15 minutes).
         */
        const val ONLINE_TTL_SECONDS = 900

        /**
         * How long a logout/deletion tombstone lives: longer than any refresh can sit on the bounded
         * background launcher, short enough to leave no lasting trace.
         */
        const val ENDED_TOMBSTONE_TTL_SECONDS = 120L
    }
}
