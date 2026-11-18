package com.pynapli.server.realtime

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Caps the sockets one account holds open on one stream, on this replica. When a new socket would
 * go over the cap, the **oldest** is closed (VIOLATED_POLICY, `TOO_MANY_STREAMS`) and the new one is
 * kept. Refusing the newest instead would lock a real user out after a network change: their old
 * socket stays half-open until the ping timeout while the app is already reconnecting. Closing the
 * oldest costs an abuser nothing they didn't open themselves, and still bounds what one account can
 * pin on a replica.
 */
internal object StreamConnectionLimiter {
    private val open = HashMap<String, ArrayDeque<DefaultWebSocketServerSession>>()

    /** Registers [session]; returns the socket that was pushed out, if any. */
    fun register(
        stream: String,
        userId: UUID,
        session: DefaultWebSocketServerSession,
        maxPerUser: Int,
    ): DefaultWebSocketServerSession? =
        synchronized(open) {
            val sockets = open.getOrPut(key(stream, userId)) { ArrayDeque() }
            sockets.addLast(session)
            if (sockets.size > maxPerUser) sockets.removeFirst() else null
        }

    fun unregister(
        stream: String,
        userId: UUID,
        session: DefaultWebSocketServerSession,
    ) {
        synchronized(open) {
            val key = key(stream, userId)
            val sockets = open[key] ?: return
            sockets.remove(session)
            if (sockets.isEmpty()) open.remove(key)
        }
    }

    fun evict(session: DefaultWebSocketServerSession) {
        session.launch { session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, TOO_MANY_STREAMS)) }
    }

    private fun key(
        stream: String,
        userId: UUID,
    ) = "$stream|$userId"

    const val TOO_MANY_STREAMS = "TOO_MANY_STREAMS"
}
