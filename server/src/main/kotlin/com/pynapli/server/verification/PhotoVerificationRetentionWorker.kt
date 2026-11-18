package com.pynapli.server.verification

import com.pynapli.server.db.JobLock
import com.pynapli.server.db.UnlockedJobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** Periodically deletes verification selfies past retention (one replica per tick, see [JobLock]). */
class PhotoVerificationRetentionWorker(
    private val service: PhotoVerificationService,
    private val intervalMillis: Long,
    private val jobLock: JobLock = UnlockedJobs,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(PhotoVerificationRetentionWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    runCatching {
                        jobLock.runExclusive("photo-verification-retention") {
                            val purged = service.purgeExpiredSelfies()
                            if (purged > 0) logger.info("Photo verification retention removed {} selfie(s)", purged)
                        }
                    }.onFailure { error ->
                        logger.warn("Photo verification retention failed: {}", error.javaClass.simpleName)
                    }
                    delay(intervalMillis)
                }
            }
    }

    override fun close() {
        scope.cancel()
    }
}
