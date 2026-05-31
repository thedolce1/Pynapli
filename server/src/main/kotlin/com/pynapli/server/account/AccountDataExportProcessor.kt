package com.pynapli.server.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Turns `requested` data-export rows into downloadable archives: claim → build archive → store →
 * mark `ready` (or `failed`). Claiming is atomic (`FOR UPDATE SKIP LOCKED`), so multiple instances
 * are safe.
 */
class AccountDataExportProcessor(
    private val repository: AccountLifecycleRepository,
    private val archiver: AccountDataExportArchiver,
    private val storage: AccountExportStorage,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(AccountDataExportProcessor::class.java)

    /** Processes one pending export. Returns false when none are pending. */
    suspend fun processNextPending(): Boolean {
        val claimed = repository.claimNextPendingExport() ?: return false
        runCatching {
            val archive = archiver.buildArchive(claimed.userId)
            val stored = storage.store(claimed.userId, claimed.id, archive)
            repository.markExportReady(claimed.id, stored.downloadUrl, clock.instant(), stored.expiresAt)
            logger.info("Data export ready request={} user={}", claimed.id, claimed.userId)
        }.onFailure { error ->
            logger.warn("Data export failed request={} user={}", claimed.id, claimed.userId, error)
            repository.markExportFailed(claimed.id, error.message ?: error::class.simpleName.orEmpty())
        }
        return true
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
