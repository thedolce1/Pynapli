package com.pynapli.server.dependencies

import com.pynapli.server.realtime.RealtimeRelay
import com.pynapli.server.realtime.RedisRealtimeRelay
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands

class RedisCache(
    private val redisUrl: String,
) : AutoCloseable {
    private val client: RedisClient = RedisClient.create(redisUrl)
    private val connection: StatefulRedisConnection<String, String> = client.connect()

    val commands: RedisCommands<String, String>
        get() = connection.sync()

    /** Carries realtime deltas between API replicas (own pub/sub connections, opened on first use). */
    private val relay = lazy { RedisRealtimeRelay(redisUrl) }
    val realtimeRelay: RealtimeRelay get() = relay.value

    override fun close() {
        if (relay.isInitialized()) runCatching { relay.value.close() }
        connection.close()
        client.shutdown()
    }
}
