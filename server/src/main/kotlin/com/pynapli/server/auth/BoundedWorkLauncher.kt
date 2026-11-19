package com.pynapli.server.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs off-request work (sign-up emails) on the application background scope with a ceiling: at
 * most [maxConcurrent] jobs touch the database pool at once, and at most [maxPending] wait or run.
 * Past that a job is dropped (counted, logged without detail). [launch] never suspends or throws,
 * so the request that queued the job answers exactly the same whether or not it was dropped; a
 * dropped sign-up just gets no email, and the person can send a new code.
 */
class BoundedWorkLauncher(
    private val scope: CoroutineScope,
    maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    private val maxPending: Int = DEFAULT_MAX_PENDING,
) {
    private val permits = Semaphore(maxConcurrent)
    private val pending = AtomicInteger(0)
    private val droppedJobs = AtomicLong(0)

    val dropped: Long get() = droppedJobs.get()

    fun launch(work: suspend () -> Unit) {
        if (pending.incrementAndGet() > maxPending) {
            pending.decrementAndGet()
            if (droppedJobs.incrementAndGet() % LOG_EVERY == 1L) {
                logger.warn("Background sign-up work is saturated; dropped {} jobs so far", droppedJobs.get())
            }
            return
        }
        scope.launch {
            try {
                permits.withPermit { work() }
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(BoundedWorkLauncher::class.java)
        const val DEFAULT_MAX_CONCURRENT = 4
        const val DEFAULT_MAX_PENDING = 1_000
        private const val LOG_EVERY = 100L
    }
}
