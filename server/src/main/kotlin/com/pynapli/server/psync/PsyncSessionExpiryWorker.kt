package com.pynapli.server.psync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Periodic Psync maintenance (PSYNC_SPEC "Session idle > expiration"). Each tick closes out every
 * session past its deadline: a started one is resolved (likes made so far are delivered, matches
 * resolve), one that never started becomes `expired`. It also refunds Pineapple Likes that were
 * never delivered.
 *
 * Requests already do this on the way in, so the worker is not the source of correctness. It is
 * what delivers the likes from a session nobody comes back to: one abandoned mid-deck, or one whose
 * partner link ended. Safe on several instances: resolution runs under a row lock and is idempotent.
 *
 * Mirrors `TonightSessionExpiryWorker`.
 */
class PsyncSessionExpiryWorker(
    private val service: PsyncSessionService,
    private val intervalMillis: Long,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(PsyncSessionExpiryWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    runCatching {
                        val closed = service.expireStale()
                        if (closed > 0) logger.info("Psync expiry closed out {} session(s)", closed)
                    }.onFailure { error ->
                        logger.warn("Psync expiry sweep failed", error)
                    }
                    delay(intervalMillis)
                }
            }
    }

    override fun close() {
        scope.cancel()
    }
}
