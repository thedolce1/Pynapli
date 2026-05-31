package com.pynapli.server.subscriptions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class PlayStoreSubscriptionSyncWorker(
    private val service: SubscriptionService,
    private val intervalMillis: Long,
    private val batchSize: Int,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(PlayStoreSubscriptionSyncWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    runCatching {
                        val summary = service.syncDuePlayStoreSubscriptions(batchSize)
                        if (summary.scanned > 0) {
                            logger.info(
                                "Play Store subscription sync scanned={} verified={} active={} grace={} pending={} expired={} revoked={} invalid={} unavailable={}",
                                summary.scanned,
                                summary.verified,
                                summary.active,
                                summary.grace,
                                summary.pending,
                                summary.expired,
                                summary.revoked,
                                summary.invalid,
                                summary.unavailable,
                            )
                        }
                    }.onFailure { error ->
                        logger.warn("Play Store subscription sync failed", error)
                    }
                    delay(intervalMillis)
                }
            }
    }

    override fun close() {
        scope.cancel()
    }
}
