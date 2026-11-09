package com.pynapli.server.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Turns `requested` data-export rows into downloadable archives: claim → build archive → store →
 * mark `ready` (or `failed`). Claiming is atomic (`FOR UPDATE SKIP LOCKED`), so multiple instances
 * are safe. A claim older than [staleAfter] is presumed dead (worker crash or redeploy) and can be
 * re-claimed; after [maxAttempts] claims the request is failed instead of retried forever.
 */
class AccountDataExportProcessor(
    private val repository: AccountLifecycleRepository,
    private val archiver: AccountDataExportArchiver,
    private val storage: AccountExportStorage,
    private val clock: Clock = Clock.systemUTC(),
    private val staleAfter: Duration = Duration.ofMinutes(15),
    private val maxAttempts: Int = 3,
) {
    private val logger = LoggerFactory.getLogger(AccountDataExportProcessor::class.java)

    /** Processes one pending export. Returns false when none are pending. */
    suspend fun processNextPending(): Boolean {
        val claimed = repository.claimNextPendingExport(staleAfter, maxAttempts) ?: return false
        runCatching {
            val archive = archiver.buildArchive(claimed.userId)
            val stored = storage.store(claimed.userId, claimed.id, archive)
            repository.markExportReady(claimed.id, stored.downloadUrl, clock.instant(), stored.expiresAt)
            logger.info("Data export ready request={}", claimed.id)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            // The exception goes to the log only: error_message is shown to the user, and exception
            // text can carry SQL, file paths, or storage URLs.
            logger.warn("Data export failed request={}", claimed.id, error)
            repository.markExportFailed(claimed.id, EXPORT_FAILED_MESSAGE)
        }
        return true
    }

    /** Fails exports whose worker died on their last allowed attempt. */
    suspend fun failAbandoned() {
        val failed = repository.failAbandonedExports(staleAfter, maxAttempts, EXPORT_FAILED_MESSAGE)
        if (failed > 0) logger.warn("Failed {} abandoned data export(s) after {} attempts", failed, maxAttempts)
    }

    companion object {
        /** User-facing; shown as the export status message when an export fails. */
        const val EXPORT_FAILED_MESSAGE = "We couldn't prepare your data export. Please request a new one."
    }
}

class AccountDataExportWorker(
    private val processor: AccountDataExportProcessor,
    private val intervalMillis: Long,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: kotlinx.coroutines.Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    // Drain all currently-pending exports, then idle until the next tick.
                    runCatching {
                        processor.failAbandoned()
                        while (isActive && processor.processNextPending()) {
                            // keep draining
                        }
                    }
                    delay(intervalMillis)
                }
            }
    }

    override fun close() {
        scope.cancel()
    }
}
