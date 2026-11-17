package com.pynapli.server.tonight

import com.pynapli.server.db.JobLock
import com.pynapli.server.db.UnlockedJobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Periodic Playing Tonight maintenance (PLAYING_TONIGHT_SPEC). Each tick does three things:
 *  1. Sweeps elapsed sessions to `expired` ("Session duration and expiry"). Reads already filter
 *     `expires_at > now`, so this is a hygiene pass that keeps the `proposed`/`active` working set
 *     small and makes status accurate for analytics — it is not the source of correctness.
 *  2. Sends the 15-minute-before-expiry reminder ("Recommended reminder: 15 minutes before
 *     expiration") to active sessions entering the lead window. The claim is atomic
 *     (`reminder_sent_at` latch + FOR UPDATE SKIP LOCKED), so a reminder fires at most once per
 *     session even across ticks / multiple instances.
 *  3. Reconciles partner presence for linked-together sessions ("Partner goes offline: pause
 *     linked-together session briefly, then end if not restored"): pauses (hides from discovery)
 *     a together session when a participant goes offline, resumes it when they return, and ends it
 *     once it stays paused past the grace window.
 *
 * Mirrors `MediaCleanupWorker`.
 */
class TonightSessionExpiryWorker(
    private val service: TonightSessionService,
    private val intervalMillis: Long,
    private val jobLock: JobLock = UnlockedJobs,
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
                        // One replica per tick; the others skip it (see JobLock).
                        jobLock.runExclusive("tonight-session-expiry") {
                            val expired = service.expireStale()
                            if (expired > 0) {
                                logger.info("Playing Tonight expiry swept {} session(s) to 'expired'", expired)
                            }
                            val reminded = service.sendExpiryReminders()
                            if (reminded > 0) {
                                logger.info("Playing Tonight reminded {} session(s) nearing expiry", reminded)
                            }
                            val reconciled = service.reconcilePresence()
                            if (reconciled.changed) {
                                logger.info(
                                    "Playing Tonight presence reconcile: {} paused, {} resumed, {} ended",
                                    reconciled.paused,
                                    reconciled.resumed,
                                    reconciled.ended,
                                )
                            }
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
