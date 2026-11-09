package com.pynapli.shared.network

import com.pynapli.shared.core.result.runCatchingCancellable
import com.pynapli.shared.network.dto.ActivityDeltaDto
import com.pynapli.shared.network.dto.MessageDeltaDto
import com.pynapli.shared.network.dto.PresenceDeltaDto
import com.pynapli.shared.network.dto.PresenceStreamSubscribeDto
import com.pynapli.shared.network.dto.PsyncRealtimeDeltaDto
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/*
 * The realtime WebSocket streams (presence, activity, messages, psync) share one reconnecting client.
 * Deltas are convenience updates: the app refetches canonical HTTP state when one arrives, so a missed
 * or duplicated delta is harmless and every disconnect simply reconnects.
 */

/** Exponential reconnect delay: [initialDelayMillis] × [factor]^attempt, capped at [maxDelayMillis]. */
data class StreamBackoff(
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

/** A stream session that stayed up this long counts as healthy and resets reconnect backoff. */
internal val HEALTHY_SESSION = 30.seconds

/** One open stream socket: text frames in, optional text frames out. */
interface StreamSession {
    suspend fun send(text: String)

    /** The next text frame, or null once the socket has closed. */
    suspend fun receiveText(): String?

    suspend fun close()
}

fun interface StreamSessionFactory {
    suspend fun connect(token: String): StreamSession
}

/**
 * Connects with the current access token, emits each decodable frame as [T], and reconnects with
 * [backoff] whenever the socket ends, until the collector cancels. Frames that don't decode are
 * skipped. [outgoing], when given, is sent up the socket for the life of each session (presence
 * uses it to resubscribe).
 */
class JsonStreamClient<T>(
    private val deserializer: DeserializationStrategy<T>,
    private val sessionFactory: StreamSessionFactory,
    private val tokenProvider: AccessTokenProvider,
    private val backoff: StreamBackoff = StreamBackoff(),
    private val json: Json = PynapliJson,
) {
    fun connect(outgoing: Flow<String>? = null): Flow<T> =
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
                val connectedAt = TimeSource.Monotonic.markNow()
                var receivedFrame = false
                try {
                    coroutineScope {
                        val senderJob = outgoing?.let { frames -> launch { frames.collect { session.send(it) } } }
                        try {
                            while (true) {
                                val text = session.receiveText() ?: break
                                val delta = runCatching { json.decodeFromString(deserializer, text) }.getOrNull() ?: continue
                                receivedFrame = true
                                emit(delta)
                            }
                        } finally {
                            senderJob?.cancel()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Fall through to the reconnect path.
                } finally {
                    runCatching { session.close() }
                }
                // The session ended: server close, network drop, or a post-upgrade auth rejection
                // (e.g. an expired access token). Always back off before reconnecting, and reset the
                // backoff only after a healthy session, so a rejected socket can't reconnect in a loop.
                if (receivedFrame || connectedAt.elapsedNow() >= HEALTHY_SESSION) attempt = 0
                delay(backoff.delayMillisFor(attempt))
                attempt += 1
            }
        }
}

typealias ActivityStreamClient = JsonStreamClient<ActivityDeltaDto>
typealias MessageStreamClient = JsonStreamClient<MessageDeltaDto>
typealias PsyncStreamClient = JsonStreamClient<PsyncRealtimeDeltaDto>

/** Presence is the one bidirectional stream: the client sends the user ids it wants deltas for. */
class PresenceStreamClient(
    private val stream: JsonStreamClient<PresenceDeltaDto>,
    private val json: Json = PynapliJson,
) {
    fun connect(userIds: Flow<Set<String>>): Flow<PresenceDeltaDto> =
        stream.connect(
            outgoing =
                userIds.map { ids ->
                    json.encodeToString(PresenceStreamSubscribeDto.serializer(), PresenceStreamSubscribeDto(userIds = ids.toList()))
                },
        )
}

/**
 * Opens a Ktor WebSocket to [streamUrl]. Native clients authenticate with the Authorization header;
 * browsers can't set WebSocket headers, so the web client sends the token as the first frame instead
 * ([sendTokenAsFirstFrame]) — never in the URL, where it would land in access logs.
 */
class KtorStreamSessionFactory(
    private val httpClient: HttpClient,
    private val streamUrl: String,
    private val sendTokenAsFirstFrame: Boolean = false,
    private val sessionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : StreamSessionFactory {
    override suspend fun connect(token: String): StreamSession {
        val outgoing = Channel<String>(capacity = Channel.BUFFERED)
        val incoming = Channel<String>(capacity = Channel.BUFFERED)
        val ready = CompletableDeferred<Unit>()
        val sessionJob: Job =
            sessionScope.launch {
                try {
                    httpClient.webSocket(
                        urlString = streamUrl,
                        request = { if (!sendTokenAsFirstFrame) header(HttpHeaders.Authorization, "Bearer $token") },
                    ) {
                        if (sendTokenAsFirstFrame) {
                            send(Frame.Text(streamAuthFrameJson(token)))
                        }
                        ready.complete(Unit)
                        val sender = launch { outgoing.consumeEach { text -> send(Frame.Text(text)) } }
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
        return ChannelStreamSession(outgoing = outgoing, incoming = incoming, job = sessionJob)
    }
}

private class ChannelStreamSession(
    private val outgoing: Channel<String>,
    private val incoming: Channel<String>,
    private val job: Job,
) : StreamSession {
    override suspend fun send(text: String) {
        outgoing.send(text)
    }

    override suspend fun receiveText(): String? = runCatchingCancellable { incoming.receive() }.getOrNull()

    override suspend fun close() {
        outgoing.close()
        job.cancel()
    }
}

/**
 * The app's four realtime streams over one WebSocket-capable [httpClient] (one connection pool,
 * instead of a client per stream). Each platform supplies the client: it needs the Ktor WebSockets
 * plugin and a WebSocket-capable engine (OkHttp on Android/desktop, Darwin on iOS, the browser on web).
 * Streams are created on first use, so a shell that never opens Psync never builds it.
 */
class RealtimeStreams(
    private val httpClient: HttpClient,
    websocketBaseUrl: String,
    private val tokenProvider: AccessTokenProvider,
    private val sendTokenAsFirstFrame: Boolean = false,
    private val backoff: StreamBackoff = StreamBackoff(),
) {
    private val baseUrl = websocketBaseUrl.trimEnd('/')

    val presence: PresenceStreamClient by lazy { PresenceStreamClient(stream(PresenceDeltaDto.serializer(), "/v1/presence/stream")) }
    val activity: ActivityStreamClient by lazy { stream(ActivityDeltaDto.serializer(), "/v1/activity/stream") }
    val message: MessageStreamClient by lazy { stream(MessageDeltaDto.serializer(), "/v1/messages/stream") }
    val psync: PsyncStreamClient by lazy { stream(PsyncRealtimeDeltaDto.serializer(), "/v1/psync/stream") }

    private fun <T> stream(
        deserializer: DeserializationStrategy<T>,
        path: String,
    ): JsonStreamClient<T> =
        JsonStreamClient(
            deserializer = deserializer,
            sessionFactory = KtorStreamSessionFactory(httpClient, baseUrl + path, sendTokenAsFirstFrame),
            tokenProvider = tokenProvider,
            backoff = backoff,
        )
}
