package com.pynapli.server.realtime

import com.pynapli.server.auth.bearerTokenOrNull
import com.pynapli.shared.network.dto.STREAM_AUTH_FRAME_TYPE
import com.pynapli.shared.network.dto.StreamAuthFrameDto
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

private val streamAuthJson = Json { ignoreUnknownKeys = true }

/** Web clients must authenticate within this window or the socket closes. */
const val STREAM_FIRST_FRAME_AUTH_TIMEOUT_MS: Long = 5_000L

/** The pre-auth frame carries one opaque token; anything bigger is refused unparsed. */
const val STREAM_AUTH_FRAME_MAX_BYTES: Int = 4_096

/**
 * Extracts the bearer token for a realtime stream socket. Native clients send an
 * Authorization header. Browsers cannot set WebSocket headers, so the web client
 * instead sends a single [StreamAuthFrameDto] as its first frame — awaited here with
 * a short timeout. Tokens are never accepted from the URL: query strings land in
 * access logs. Returns null when no valid token arrives either way.
 */
suspend fun DefaultWebSocketServerSession.awaitStreamToken(timeoutMillis: Long = STREAM_FIRST_FRAME_AUTH_TIMEOUT_MS): String? {
    call
        .bearerTokenOrNull()
        ?.let { return it }

    return withTimeoutOrNull(timeoutMillis) {
        val frame = incoming.receiveCatching().getOrNull() ?: return@withTimeoutOrNull null
        val textFrame = frame as? Frame.Text ?: return@withTimeoutOrNull null
        if (textFrame.data.size > STREAM_AUTH_FRAME_MAX_BYTES) return@withTimeoutOrNull null
        val text = textFrame.readText()
        runCatching { streamAuthJson.decodeFromString(StreamAuthFrameDto.serializer(), text) }
            .getOrNull()
            ?.takeIf { it.type == STREAM_AUTH_FRAME_TYPE }
            ?.token
            ?.takeIf { it.isNotEmpty() }
    }
}
