package com.pynapli.server.realtime

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Carries realtime deltas between API replicas. A user's socket lives on one replica while the
 * request that produced their delta (a message, a like, a session change) may land on another, so
 * every broadcaster publishes here and delivers what other replicas publish to its own sockets.
 *
 * Fire-and-forget by design: deltas are convenience updates and clients refetch HTTP state, so a
 * lost relay message costs freshness, never correctness.
 */
interface RealtimeRelay : AutoCloseable {
    fun publish(
        channel: String,
        message: String,
    )

    fun subscribe(
        channel: String,
        onMessage: (String) -> Unit,
    )

    override fun close() {}
}

/** Single-replica (and test) default: nothing crosses process boundaries. */
object LocalOnlyRealtimeRelay : RealtimeRelay {
    override fun publish(
        channel: String,
        message: String,
    ) {}

    override fun subscribe(
        channel: String,
        onMessage: (String) -> Unit,
    ) {}
}

/**
 * Redis pub/sub relay. One subscriber connection (Lettuce restores its subscriptions after a
 * reconnect) and one connection for async publishes, so a publish never blocks a request thread.
 */
class RedisRealtimeRelay(
    redisUrl: String,
) : RealtimeRelay {
    private val logger = LoggerFactory.getLogger(RedisRealtimeRelay::class.java)
    private val client: RedisClient = RedisClient.create(redisUrl)
    private val publisher: StatefulRedisConnection<String, String> = client.connect()
    private val subscriber: StatefulRedisPubSubConnection<String, String> = client.connectPubSub()
    private val handlers = ConcurrentHashMap<String, CopyOnWriteArrayList<(String) -> Unit>>()

    init {
        subscriber.addListener(
            object : RedisPubSubAdapter<String, String>() {
                override fun message(
                    channel: String,
                    message: String,
                ) {
                    handlers[channel]?.forEach { handler ->
                        runCatching { handler(message) }
                            .onFailure { error -> logger.warn("Realtime relay handler failed on {}: {}", channel, error.message) }
                    }
                }
            },
        )
    }

    override fun publish(
        channel: String,
        message: String,
    ) {
        publisher.async().publish(channel, message).exceptionally { error ->
            logger.warn("Realtime relay publish failed on {}: {}", channel, error.message)
            null
        }
    }

    override fun subscribe(
        channel: String,
        onMessage: (String) -> Unit,
    ) {
        val existing = handlers.computeIfAbsent(channel) { CopyOnWriteArrayList() }
        val first = existing.isEmpty()
        existing += onMessage
        if (first) subscriber.sync().subscribe(channel)
    }

    override fun close() {
        runCatching { subscriber.close() }
        runCatching { publisher.close() }
        runCatching { client.shutdown() }
    }
}
