package com.pynapli.server.media

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
import java.time.Instant

/**
 * Purges stale `media_assets` rows so abandoned, rejected, and soft-deleted uploads don't
 * accumulate. `media_album_items` cascade on asset delete, so removing an asset also removes it from
 * any album. The underlying storage objects are expired separately by GCS bucket **lifecycle rules**
 * on the `media/` prefix (the recommended pattern) — the app is authoritative for DB state only.
 */
class MediaCleanupService(
    private val repository: MediaCleanupRepository,
    private val abandonedUploadGrace: Duration = Duration.ofHours(24),
    private val rejectedRetention: Duration = Duration.ofDays(7),
    private val softDeletedRetention: Duration = Duration.ofDays(30),
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun purgeStaleAssets(): MediaCleanupSummary {
        val now = clock.instant()
        return repository.purgeStaleAssets(
            abandonedBefore = now.minus(abandonedUploadGrace),
            rejectedBefore = now.minus(rejectedRetention),
            softDeletedBefore = now.minus(softDeletedRetention),
        )
    }
}

interface MediaCleanupRepository {
    /**
     * Deletes (a) `pending_upload` assets created before [abandonedBefore] (never completed),
     * (b) `rejected` assets last updated before [rejectedBefore], and (c) soft-deleted assets whose
     * `deleted_at` is before [softDeletedBefore]. Returns how many of each were removed.
     */
    suspend fun purgeStaleAssets(
        abandonedBefore: Instant,
        rejectedBefore: Instant,
        softDeletedBefore: Instant,
    ): MediaCleanupSummary
}

data class MediaCleanupSummary(
    val abandonedUploads: Int,
    val rejectedAssets: Int,
    val softDeletedAssets: Int,
) {
    val total: Int get() = abandonedUploads + rejectedAssets + softDeletedAssets
}

class MediaCleanupWorker(
    private val service: MediaCleanupService,
    private val intervalMillis: Long,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(MediaCleanupWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: kotlinx.coroutines.Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    runCatching {
                        val summary = service.purgeStaleAssets()
                        if (summary.total > 0) {
                            logger.info(
                                "Media cleanup removed total={} abandoned={} rejected={} softDeleted={}",
                                summary.total,
                                summary.abandonedUploads,
                                summary.rejectedAssets,
                                summary.softDeletedAssets,
                            )
                        }
                    }.onFailure { error ->
                        logger.warn("Media cleanup failed", error)
                    }
                    delay(intervalMillis)
                }
            }
    }

    override fun close() {
        scope.cancel()
    }
}
