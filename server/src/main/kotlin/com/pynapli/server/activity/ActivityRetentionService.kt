package com.pynapli.server.activity

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
import java.time.Clock
import java.time.Instant

class ActivityRetentionService(
    private val repository: ActivityRetentionRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun applyProductVisibleRetention(): ActivityRetentionSummary = repository.hideExpiredProductVisibleEvents(clock.instant())
}

interface ActivityRetentionRepository {
    suspend fun hideExpiredProductVisibleEvents(now: Instant): ActivityRetentionSummary
}

data class ActivityRetentionSummary(
    val hiddenTotal: Int,
    val hiddenByType: Map<String, Int>,
)

class ActivityRetentionWorker(
    private val service: ActivityRetentionService,
    private val intervalMillis: Long,
    private val jobLock: JobLock = UnlockedJobs,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(ActivityRetentionWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: kotlinx.coroutines.Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    runCatching {
                        // One replica per tick; the others skip it (see JobLock).
                        jobLock.runExclusive("activity-retention") {
                            val summary = service.applyProductVisibleRetention()
                            if (summary.hiddenTotal > 0) {
                                logger.info(
                                    "Activity retention hid product-visible events total={} byType={}",
                                    summary.hiddenTotal,
                                    summary.hiddenByType,
                                )
                            }
                        }
                    }.onFailure { error ->
                        logger.warn("Activity retention failed", error)
                    }
                    delay(intervalMillis)
                }
            }
    }

    override fun close() {
        scope.cancel()
    }
}
