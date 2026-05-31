package com.pynapli.shared.network

import com.pynapli.shared.network.dto.PresenceDeltaDto
import com.pynapli.shared.network.dto.PresenceStreamSubscribeDto
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class PresenceStreamBackoff(
    val initialDelayMillis: Long = 1_000L,
    val maxDelayMillis: Long = 30_000L,
    val factor: Double = 2.0,
) {
    fun delayMillisFor(attempt: Int): Long {
        if (attempt <= 0) return initialDelayMillis
        var current = initialDelayMillis.toDouble()
        repeat(attempt) {
            current *= factor
            if (current >= maxDelayMillis) return maxDelayMillis
        }
        return current.toLong()
    }
}

interface PresenceStreamSession {
    suspend fun send(text: String)

    suspend fun receiveText(): String?

    suspend fun close()
}

interface PresenceStreamSessionFactory {
    suspend fun connect(token: String): PresenceStreamSession
}

class PresenceStreamClient(
    private val sessionFactory: PresenceStreamSessionFactory,
    private val tokenProvider: AccessTokenProvider,
    private val backoff: PresenceStreamBackoff = PresenceStreamBackoff(),
    private val json: Json = PynapliJson,
) {
    fun connect(userIds: Flow<Set<String>>): Flow<PresenceDeltaDto> =
        flow {
            var attempt = 0
            while (currentCoroutineContext().isActive) {
                val token = tokenProvider.accessToken()
                if (token.isNullOrEmpty()) {
                    delay(backoff.delayMillisFor(attempt))
                    attempt += 1
                    continue
                }
                val session =
                    try {
                        sessionFactory.connect(token)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        delay(backoff.delayMillisFor(attempt))
                        attempt += 1
                        continue
                    }
                attempt = 0
                try {
                    coroutineScope {
                        val senderJob =
                            launch {
                                userIds.collect { ids ->
                                    val payload =
                                        json.encodeToString(
                                            PresenceStreamSubscribeDto.serializer(),
                                            PresenceStreamSubscribeDto(userIds = ids.toList()),
                                        )
                                    session.send(payload)
                                }
                            }
                        try {
                            while (true) {
                                val text = session.receiveText() ?: break
                                val delta =
                                    runCatching {
                                        json.decodeFromString(PresenceDeltaDto.serializer(), text)
                                    }.getOrNull() ?: continue
                                emit(delta)
                            }
                        } finally {
                            senderJob.cancel()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Fall through to reconnect path.
                } finally {
                    runCatching { session.close() }
                }
            }
        }
}

class KtorPresenceStreamSessionFactory(
    private val httpClient: HttpClient,
    private val streamUrl: String,
    private val sessionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : PresenceStreamSessionFactory {
    override suspend fun connect(token: String): PresenceStreamSession {
        val outgoing = Channel<String>(capacity = Channel.BUFFERED)
        val incoming = Channel<String>(capacity = Channel.BUFFERED)
        val ready = CompletableDeferred<Unit>()
        val sessionJob: Job =
            sessionScope.launch {
                try {
                    httpClient.webSocket(
                        urlString = streamUrl,
                        request = { header(HttpHeaders.Authorization, "Bearer $token") },
                    ) {
                        ready.complete(Unit)
                        val sender =
                            launch {
                                outgoing.consumeEach { text -> send(Frame.Text(text)) }
                            }
                        try {
                            for (frame in this.incoming) {
                                if (frame is Frame.Text) incoming.send(frame.readText())
                            }
                        } finally {
                            sender.cancel()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (!ready.isCompleted) ready.completeExceptionally(e)
                } finally {
                    outgoing.close()
                    incoming.close()
                }
            }
        ready.await()
        return ChannelPresenceStreamSession(outgoing = outgoing, incoming = incoming, job = sessionJob)
    }
}

private class ChannelPresenceStreamSession(
    private val outgoing: Channel<String>,
    private val incoming: Channel<String>,
    private val job: Job,
) : PresenceStreamSession {
    override suspend fun send(text: String) {
        outgoing.send(text)
    }

    override suspend fun receiveText(): String? = runCatching { incoming.receive() }.getOrNull()

    override suspend fun close() {
        outgoing.close()
        job.cancel()
    }
}

/**
 * Builds a [PresenceStreamClient] using a platform-supplied WebSocket-capable [HttpClient].
 * The HTTP client must have the Ktor [io.ktor.client.plugins.websocket.WebSockets] plugin installed
 * and use an engine that supports WebSockets (OkHttp on Android, Darwin on iOS, CIO/Java on JVM).
 */
fun buildPresenceStreamClient(
    websocketHttpClient: HttpClient,
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
    backoff: PresenceStreamBackoff = PresenceStreamBackoff(),
): PresenceStreamClient =
    PresenceStreamClient(
        sessionFactory =
            KtorPresenceStreamSessionFactory(
                httpClient = websocketHttpClient,
                streamUrl = websocketBaseUrl.trimEnd('/') + "/v1/presence/stream",
            ),
        tokenProvider = tokenProvider,
        backoff = backoff,
    )
