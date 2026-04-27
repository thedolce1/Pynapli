package com.pynapli.server.dependencies

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands

class RedisCache(
    redisUrl: String
) : AutoCloseable {
    private val client: RedisClient = RedisClient.create(redisUrl)
    private val connection: StatefulRedisConnection<String, String> = client.connect()

    val commands: RedisCommands<String, String>
        get() = connection.sync()

    override fun close() {
        connection.close()
        client.shutdown()
    }
}
