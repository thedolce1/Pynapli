package com.pynapli.server.tonight

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Sweeps elapsed Playing Tonight sessions to `expired` (PLAYING_TONIGHT_SPEC: "Session duration and
 * expiry"). Reads already filter `expires_at > now`, so this is a hygiene pass that keeps the
 * `proposed`/`active` working set small and makes status accurate for analytics — it is not the
 * source of correctness. Mirrors `MediaCleanupWorker`.
 */
class TonightSessionExpiryWorker(
    private val service: TonightSessionService,
    private val intervalMillis: Long,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(TonightSessionExpiryWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: kotlinx.coroutines.Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    runCatching {
                        val expired = service.expireStale()
                        if (expired > 0) {
                            logger.info("Playing Tonight expiry swept {} session(s) to 'expired'", expired)
                        }
                    }.onFailure { error ->
                        logger.warn("Playing Tonight expiry sweep failed", error)
                    }
                    delay(intervalMillis)
                }
            }
    }

    override fun close() {
        scope.cancel()
    }
}
