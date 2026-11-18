package com.pynapli.server.swipe

import com.pynapli.server.db.JobLock
import com.pynapli.server.db.UnlockedJobs
import com.pynapli.server.db.withConnection
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
import javax.sql.DataSource

data class MatchSideEffectDrainResult(
    val delivered: Int,
    val failed: Int,
    val pruned: Int = 0,
)

/**
 * Delivers match side effects whose request never did (it crashed or lost its connection between
 * the commit and the delivery). Rows are claimed with a lease, so overlapping drains never deliver
 * the same row at once; a failed row backs off exponentially and is given up after [maxAttempts]
 * (it stays in the table, `delivered_at IS NULL`, with the error class, for an operator).
 */
class MatchSideEffectDrainer(
    private val dataSource: DataSource,
    private val delivery: MatchSideEffectDelivery,
    private val clock: Clock = Clock.systemUTC(),
    private val batchSize: Int = 100,
    private val claimLease: Duration = Duration.ofMinutes(5),
    private val maxAttempts: Int = 12,
    private val keepDelivered: Duration = Duration.ofDays(7),
) {
    private val logger = LoggerFactory.getLogger(MatchSideEffectDrainer::class.java)

    suspend fun drainOnce(): MatchSideEffectDrainResult {
        val now = clock.instant()
        val claimed =
            dataSource.withConnection { connection ->
                connection.claimDueMatchSideEffects(now, batchSize, claimLease, maxAttempts)
            }
        var delivered = 0
        var failed = 0
        for ((effect, attempts) in claimed) {
            try {
                delivery.deliver(effect)
                dataSource.withConnection { it.markMatchSideEffectsDelivered(listOf(effect.id), clock.instant()) }
                delivered++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed++
                val retryAt = clock.instant().plus(backoff(attempts))
                dataSource.withConnection { it.markMatchSideEffectFailed(effect.id, error.javaClass.name, retryAt) }
                if (attempts >= maxAttempts) {
                    logger.error("Match side effect {} ({}) given up after {} attempts: {}", effect.id, effect.kind, attempts, error.javaClass.simpleName)
                } else {
                    logger.warn("Match side effect {} ({}) failed; retrying at {}: {}", effect.id, effect.kind, retryAt, error.javaClass.simpleName)
                }
            }
        }
        val pruned = dataSource.withConnection { it.pruneDeliveredMatchSideEffects(now.minus(keepDelivered)) }
        return MatchSideEffectDrainResult(delivered = delivered, failed = failed, pruned = pruned)
    }

    // 30 s, 1 min, 2 min, ... capped at an hour. [attempts] already counts the one that failed.
    private fun backoff(attempts: Int): Duration {
        val seconds = 30L shl (attempts - 1).coerceIn(0, 7)
        return Duration.ofSeconds(seconds.coerceAtMost(3_600))
    }
}

/** Runs [MatchSideEffectDrainer] on one replica at a time (see [JobLock]). */
class MatchSideEffectDrainWorker(
    private val drainer: MatchSideEffectDrainer,
    private val intervalMillis: Long,
    private val jobLock: JobLock = UnlockedJobs,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(MatchSideEffectDrainWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: kotlinx.coroutines.Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    runCatching {
                        jobLock.runExclusive("match-side-effect-drain") {
                            val result = drainer.drainOnce()
                            if (result.delivered > 0 || result.failed > 0) {
                                logger.info(
                                    "Match side-effect drain: {} delivered late, {} failed",
                                    result.delivered,
                                    result.failed,
                                )
                            }
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        logger.warn("Match side-effect drain failed", error)
                    }
                    delay(intervalMillis)
                }
            }
    }

    override fun close() {
        scope.cancel()
    }
}
