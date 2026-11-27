package com.pynapli.server.media

import com.pynapli.server.db.JobLock
import com.pynapli.server.db.UnlockedJobs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.UUID

/**
 * Makes and retires grid thumbnails (G2-2 ticket 1; TRUST_AND_SAFETY_SPEC "Photo pre-screening":
 * a thumbnail exists only for an approved public profile photo).
 *
 * - [generate]: claim (re-checks eligibility) → read the stored, screened original → render →
 *   write the variant → mark ready (re-checks eligibility again). If the photo stopped being
 *   eligible at any point (held, rejected, removed, deleted, taken out of the profile album), the
 *   written object is deleted and the row forgets it, so no public variant of it ever stands.
 * - [reconcile]: the backfill (eligible photos without a ready thumbnail, a bounded batch per tick)
 *   plus the cleanup walk (any row with a variant whose photo is no longer eligible gets its object
 *   deleted). That one walk covers every removal path (moderator rejection, report removal, owner
 *   removal, account deletion's soft delete) without hooking each of them.
 *
 * Logs carry the asset id and an outcome word only: never a URL, object key or byte.
 */
class MediaThumbnailService(
    private val repository: MediaThumbnailRepository,
    private val store: MediaThumbnailStore,
    private val renderer: ThumbnailRenderer = ThumbnailRenderer(),
    private val maxAttempts: Int = 3,
) {
    private val logger = LoggerFactory.getLogger(MediaThumbnailService::class.java)

    suspend fun generate(mediaAssetId: UUID): ThumbnailOutcome {
        val source = repository.claim(mediaAssetId, MediaThumbnails.VARIANT) ?: return ThumbnailOutcome.SKIPPED
        if (!store.supports(source.storageProvider)) {
            // Firebase-era rows: nothing to read with. Terminal; the original keeps serving.
            repository.markUnrenderable(mediaAssetId)
            return ThumbnailOutcome.UNRENDERABLE
        }
        val original =
            try {
                store.readOriginal(source)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.warn("Thumbnail read failed for asset {} ({})", mediaAssetId, error.javaClass.simpleName)
                repository.markAttemptFailed(mediaAssetId, maxAttempts)
                return ThumbnailOutcome.RETRY
            }
        val rendered =
            try {
                withContext(Dispatchers.Default) { renderer.render(original) }
            } catch (error: ThumbnailRenderException) {
                logger.info("Thumbnail not possible for asset {} ({})", mediaAssetId, error.reason.substringBefore(':'))
                repository.markUnrenderable(mediaAssetId)
                return ThumbnailOutcome.UNRENDERABLE
            }
        try {
            store.writeThumbnail(source, rendered.bytes)
        } catch (error: CancellationException) {
            // The variant column stays set: the reconciler deletes anything half-written.
            throw error
        } catch (error: Exception) {
            logger.warn("Thumbnail write failed for asset {} ({})", mediaAssetId, error.javaClass.simpleName)
            repository.markAttemptFailed(mediaAssetId, maxAttempts)
            return ThumbnailOutcome.RETRY
        }
        if (repository.markReady(mediaAssetId, MediaThumbnails.VARIANT)) return ThumbnailOutcome.READY
        // Lost the race with a hold/removal/deletion: what we wrote must not stand.
        retire(mediaAssetId, source.storageProvider, source.storageBucket)
        return ThumbnailOutcome.RETIRED
    }

    /** One worker tick: up to [backfillLimit] generations, then up to [sweepLimit] rows checked. */
    suspend fun reconcile(
        backfillLimit: Int,
        sweepLimit: Int,
        sweepAfter: UUID?,
    ): ReconcileSummary {
        var ready = 0
        var failed = 0
        for (id in repository.listMissing(backfillLimit)) {
            when (runCatchingNonCancel { generate(id) }) {
                ThumbnailOutcome.READY -> ready++
                ThumbnailOutcome.SKIPPED, ThumbnailOutcome.RETIRED -> Unit
                else -> failed++
            }
        }
        val rows = repository.listWithVariant(sweepAfter, sweepLimit)
        var retired = 0
        for (row in rows) {
            if (row.eligible || row.leaseLive) continue
            if (retire(row.mediaAssetId, row.storageProvider, row.storageBucket)) retired++
        }
        // Walk on from the last row; wrap to the start once the walk reaches the end.
        val nextAfter = if (rows.size < sweepLimit) null else rows.last().mediaAssetId
        return ReconcileSummary(generated = ready, failed = failed, retired = retired, nextSweepAfter = nextAfter)
    }

    /** Deletes the variant object, and only once it is gone forgets it on the row. */
    private suspend fun retire(
        mediaAssetId: UUID,
        storageProvider: String,
        storageBucket: String,
    ): Boolean {
        val deleted =
            try {
                store.deleteThumbnail(storageProvider, storageBucket, mediaAssetId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                false
            }
        if (!deleted) {
            logger.warn("Thumbnail delete failed for asset {}; retried on the next sweep", mediaAssetId)
            return false
        }
        repository.clearVariant(mediaAssetId)
        return true
    }

    private suspend fun runCatchingNonCancel(block: suspend () -> ThumbnailOutcome): ThumbnailOutcome =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("Thumbnail generation failed ({})", error.javaClass.simpleName)
            ThumbnailOutcome.RETRY
        }
}

/**
 * Told when a profile photo became approved (pre-screen auto-approve or a moderator). Must return at
 * once and never throw: the approval is already decided and must not depend on it.
 */
fun interface ProfilePhotoApprovedListener {
    fun profilePhotoApproved(mediaAssetId: UUID)
}

enum class ThumbnailOutcome { READY, SKIPPED, RETRY, UNRENDERABLE, RETIRED }

data class ReconcileSummary(
    val generated: Int,
    val failed: Int,
    val retired: Int,
    val nextSweepAfter: UUID?,
)

/**
 * Starts a generation right after a profile photo is approved (auto-approve after pre-screening, or
 * a moderator's approval), off the request path and bounded to [concurrency] at a time. Best effort:
 * it never fails or delays the approval, and the backfill worker picks up anything it misses.
 */
class MediaThumbnailScheduler(
    private val service: MediaThumbnailService,
    concurrency: Int = 2,
) : ProfilePhotoApprovedListener,
    AutoCloseable {
    private val logger = LoggerFactory.getLogger(MediaThumbnailScheduler::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(concurrency)

    override fun profilePhotoApproved(mediaAssetId: UUID) {
        scope.launch {
            permits.withPermit {
                try {
                    service.generate(mediaAssetId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logger.warn("Thumbnail generation for asset {} failed ({})", mediaAssetId, error.javaClass.simpleName)
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}

/**
 * The rate-limited backfill + cleanup loop: every [intervalMillis], one replica (JobLock) generates
 * at most [backfillPerTick] thumbnails, one after another, and checks [sweepPerTick] rows that hold a
 * variant. At the defaults (20 per 30 s) a backlog of 10k photos drains in about four hours without
 * competing with live traffic. Idempotent: a re-run finds nothing to do.
 */
class MediaThumbnailWorker(
    private val service: MediaThumbnailService,
    private val intervalMillis: Long,
    private val backfillPerTick: Int,
    private val sweepPerTick: Int,
    private val jobLock: JobLock = UnlockedJobs,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(MediaThumbnailWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: kotlinx.coroutines.Job? = null
    private var sweepAfter: UUID? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    runCatching {
                        jobLock.runExclusive("media-thumbnails") {
                            val summary = service.reconcile(backfillPerTick, sweepPerTick, sweepAfter)
                            sweepAfter = summary.nextSweepAfter
                            if (summary.generated + summary.failed + summary.retired > 0) {
                                logger.info(
                                    "Media thumbnails generated={} failed={} retired={}",
                                    summary.generated,
                                    summary.failed,
                                    summary.retired,
                                )
                            }
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        logger.warn("Media thumbnail tick failed ({})", error.javaClass.simpleName)
                    }
                    delay(intervalMillis)
                }
            }
    }

    override fun close() {
        scope.cancel()
    }
}
