package com.pynapli.server.realtime

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.util.UUID

internal val streamJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

/** How often an open stream re-checks that its session is still valid. */
const val STREAM_SESSION_RECHECK_MS: Long = 60_000L

/**
 * Authenticates a realtime socket: the Authorization header for native clients, or a first
 * StreamAuthFrameDto for browsers (see [awaitStreamToken]). On failure it closes the socket with
 * VIOLATED_POLICY and the reason, and returns null.
 *
 * Once open, the socket re-checks its session every [recheckEveryMillis] and closes the same way
 * when it is no longer valid: signed out (here or on "sign out other devices"), expired, or the
 * account suspended, banned or deleted. Without this a socket opened before a ban kept receiving
 * deltas until the client happened to disconnect. The client reconnects with its current token.
 */
suspend fun DefaultWebSocketServerSession.authenticateStream(
    authService: AuthService,
    recheckEveryMillis: Long = STREAM_SESSION_RECHECK_MS,
): UUID? {
    val token = awaitStreamToken()
    if (token == null) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "UNAUTHENTICATED"))
        return null
    }
    val viewerId =
        try {
            authService.authenticate(token).id
        } catch (e: AuthException) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.code))
            return null
        }
    launch {
        while (true) {
            delay(recheckEveryMillis)
            try {
                authService.authenticate(token)
            } catch (e: AuthException) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.code))
                return@launch
            }
        }
    }
    return viewerId
}

/**
 * A server-driven stream at [path]: after authentication, every delta from [deltas] addressed to the
 * viewer ([recipientOf]) is sent as [dtoSerializer] JSON. Incoming frames are ignored to keep the
 * contract small. Deltas are convenience updates (clients refetch HTTP state), so a failed send is
 * logged and skipped, never fatal.
 */
internal fun <D, Dto> Route.recipientStream(
    path: String,
    authService: AuthService,
    deltas: Flow<D>,
    recipientOf: (D) -> UUID,
    toDto: (D) -> Dto,
    dtoSerializer: KSerializer<Dto>,
    logger: Logger,
    // Runs for as long as the socket is open (e.g. a presence heartbeat); cancelled on close.
    onOpen: (suspend (UUID) -> Unit)? = null,
) {
    webSocket(path) {
        val viewerId = authenticateStream(authService) ?: return@webSocket
        val openJob = onOpen?.let { hook -> launch { hook(viewerId) } }
        val emitterJob =
            launch {
                deltas
                    .buffer(RealtimeFanout.PER_CONNECTION_BUFFER, BufferOverflow.DROP_OLDEST)
                    .filter { delta -> recipientOf(delta) == viewerId }
                    .collect { delta ->
                        runCatching {
                            send(streamJson.encodeToString(dtoSerializer, toDto(delta)))
                        }.onFailure { error ->
                            logger.debug("Failed to send {} delta to viewer={}: {}", path, viewerId, error.message)
                        }
                    }
            }
        try {
            for (frame in incoming) {
                // Server-driven: incoming frames are ignored.
            }
        } catch (_: ClosedReceiveChannelException) {
            // Client disconnected; fall through.
        } finally {
            emitterJob.cancel()
            openJob?.cancel()
        }
    }
}
