package com.pynapli.shared.network

import com.pynapli.shared.network.dto.ActivityDeltaDto
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class ActivityStreamBackoff(
    val initialDelayMillis: Long = 1_000L,
    val maxDelayMillis: Long = 30_000L,
    val factor: Double = 2.0
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

interface ActivityStreamSession {
    suspend fun receiveText(): String?
    suspend fun close()
}

interface ActivityStreamSessionFactory {
    suspend fun connect(token: String): ActivityStreamSession
}

class ActivityStreamClient(
    private val sessionFactory: ActivityStreamSessionFactory,
    private val tokenProvider: AccessTokenProvider,
    private val backoff: ActivityStreamBackoff = ActivityStreamBackoff(),
    private val json: Json = PynapliJson
) {
    fun connect(): Flow<ActivityDeltaDto> = flow {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val token = tokenProvider.accessToken()
            if (token.isNullOrEmpty()) {
                delay(backoff.delayMillisFor(attempt))
                attempt += 1
                continue
            }
            val session = try {
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
                while (true) {
                    val text = session.receiveText() ?: break
                    val delta = runCatching {
                        json.decodeFromString(ActivityDeltaDto.serializer(), text)
                    }.getOrNull() ?: continue
                    emit(delta)
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

class KtorActivityStreamSessionFactory(
    private val httpClient: HttpClient,
    private val streamUrl: String,
    private val sessionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : ActivityStreamSessionFactory {
    override suspend fun connect(token: String): ActivityStreamSession {
        val incoming = Channel<String>(capacity = Channel.BUFFERED)
        val ready = CompletableDeferred<Unit>()
        val sessionJob: Job = sessionScope.launch {
            try {
                httpClient.webSocket(
                    urlString = streamUrl,
                    request = { header(HttpHeaders.Authorization, "Bearer $token") }
                ) {
                    ready.complete(Unit)
                    for (frame in this.incoming) {
                        if (frame is Frame.Text) incoming.send(frame.readText())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!ready.isCompleted) ready.completeExceptionally(e)
            } finally {
                incoming.close()
            }
        }
        ready.await()
        return ChannelActivityStreamSession(incoming = incoming, job = sessionJob)
    }
}

private class ChannelActivityStreamSession(
    private val incoming: Channel<String>,
    private val job: Job
) : ActivityStreamSession {
    override suspend fun receiveText(): String? =
        runCatching { incoming.receive() }.getOrNull()

    override suspend fun close() {
        job.cancel()
    }
}

fun buildActivityStreamClient(
    websocketHttpClient: HttpClient,
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
    backoff: ActivityStreamBackoff = ActivityStreamBackoff()
): ActivityStreamClient = ActivityStreamClient(
    sessionFactory = KtorActivityStreamSessionFactory(
        httpClient = websocketHttpClient,
        streamUrl = websocketBaseUrl.trimEnd('/') + "/v1/activity/stream"
    ),
    tokenProvider = tokenProvider,
    backoff = backoff
)
