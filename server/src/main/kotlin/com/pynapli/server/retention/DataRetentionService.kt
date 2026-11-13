package com.pynapli.server.retention

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
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Duration
import javax.sql.DataSource

/**
 * Deletes data Pynapli has promised not to keep, and housekeeping rows nothing reads any more.
 *
 * - **Expired disappearing messages.** A conversation timer hides `user` messages at once
 *   (MESSAGING_SPEC); the rows are kept for [expiredMessageGrace] so moderation can still act, then
 *   deleted with their chat photo (soft-deleted for the media cleanup). A message stays while it
 *   is in the open review queue or the subject of an open report (legal hold). Report evidence is
 *   copied when the report is filed, so it outlives the message either way.
 * - **Dead sessions** revoked or past refresh expiry for [deadSessionGrace] (kept that long so the
 *   signed-in-devices history and abuse investigations still see them).
 * - **Password reset codes** expired or used more than a day ago.
 * - **Play notification payloads** (`raw_payload`) older than [rtdnPayloadGrace]: the processed
 *   result is kept, the raw purchase JSON is not needed after that.
 *
 * Every step deletes in batches of [batchSize] so a backlog never holds long locks.
 */
class DataRetentionService(
    private val dataSource: DataSource,
    private val expiredMessageGrace: Duration = Duration.ofDays(30),
    private val deadSessionGrace: Duration = Duration.ofDays(30),
    private val rtdnPayloadGrace: Duration = Duration.ofDays(90),
    private val batchSize: Int = 500,
) {
    suspend fun runOnce(): DataRetentionSummary =
        withContext(Dispatchers.IO) {
            DataRetentionSummary(
                expiredMessages = repeatBatches { it.purgeExpiredMessages() },
                deadSessions = repeatBatches { it.deleteBatch(DELETE_DEAD_SESSIONS, deadSessionGrace.seconds) },
                passwordResetCodes = repeatBatches { it.deleteBatch(DELETE_OLD_RESET_CODES, Duration.ofDays(1).seconds) },
                rtdnPayloads = repeatBatches { it.deleteBatch(CLEAR_OLD_RTDN_PAYLOADS, rtdnPayloadGrace.seconds) },
            )
        }

    /** Runs [step] until a batch comes back short; each batch is its own transaction. */
    private fun repeatBatches(step: (Connection) -> Int): Int {
        var total = 0
        while (true) {
            val count =
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        step(connection).also { connection.commit() }
                    } catch (error: Throwable) {
                        connection.rollback()
                        throw error
                    } finally {
                        connection.autoCommit = true
                    }
                }
            total += count
            if (count < batchSize) return total
        }
    }

    private fun Connection.purgeExpiredMessages(): Int {
        val purged =
            prepareStatement(PURGE_EXPIRED_MESSAGES).use { statement ->
                statement.setLong(1, expiredMessageGrace.seconds)
                statement.setInt(2, batchSize)
                statement.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getObject("media_asset_id") as java.util.UUID?) }
                }
            }
        val photos = purged.filterNotNull().distinct()
        if (photos.isNotEmpty()) {
            prepareStatement(SOFT_DELETE_ORPHANED_CHAT_PHOTOS).use { statement ->
                statement.setArray(1, createArrayOf("uuid", photos.toTypedArray()))
                statement.executeUpdate()
            }
        }
        return purged.size
    }

    private fun Connection.deleteBatch(
        sql: String,
        graceSeconds: Long,
    ): Int =
        prepareStatement(sql).use { statement ->
            statement.setLong(1, graceSeconds)
            statement.setInt(2, batchSize)
            statement.executeUpdate()
        }

    private companion object {
        // The conversation's effective timer is the strictest one among active participants, the
        // same rule the read path uses to hide messages (effectiveExpirySeconds).
        val PURGE_EXPIRED_MESSAGES =
            """
            WITH timers AS (
                SELECT conversation_id, MIN(message_expiry_seconds) AS seconds
                FROM conversation_participants
                WHERE left_at IS NULL
                  AND invitation_status = 'active'
                  AND message_expiry_seconds IS NOT NULL
                GROUP BY conversation_id
            ),
            doomed AS (
                SELECT m.id
                FROM messages m
                JOIN timers t ON t.conversation_id = m.conversation_id
                WHERE m.message_type = 'user'
                  AND m.created_at < now() - make_interval(secs => t.seconds) - make_interval(secs => ?)
                  AND NOT EXISTS (
                      SELECT 1 FROM message_review_queue q
                      WHERE q.message_id = m.id AND q.status = 'open'
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM safety_reports r
                      WHERE r.target_type = 'message'
                        AND r.target_id = m.id::text
                        AND r.status IN ('open', 'under_review')
                  )
                LIMIT ?
            )
            DELETE FROM messages
            WHERE id IN (SELECT id FROM doomed)
            RETURNING media_asset_id
            """.trimIndent()

        // A chat photo goes with its message unless something else still shows it.
        val SOFT_DELETE_ORPHANED_CHAT_PHOTOS =
            """
            UPDATE media_assets a
            SET deleted_at = now(), updated_at = now()
            WHERE a.id = ANY(?)
              AND a.deleted_at IS NULL
              AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.media_asset_id = a.id)
              AND NOT EXISTS (
                  SELECT 1 FROM media_album_items i
                  WHERE i.media_asset_id = a.id AND i.removed_at IS NULL
              )
            """.trimIndent()

        val DELETE_DEAD_SESSIONS =
            """
            DELETE FROM user_sessions
            WHERE id IN (
                SELECT id FROM user_sessions
                WHERE COALESCE(revoked_at, refresh_expires_at) < now() - make_interval(secs => ?)
                LIMIT ?
            )
            """.trimIndent()

        val DELETE_OLD_RESET_CODES =
            """
            DELETE FROM password_reset_codes
            WHERE id IN (
                SELECT id FROM password_reset_codes
                WHERE COALESCE(consumed_at, expires_at) < now() - make_interval(secs => ?)
                LIMIT ?
            )
            """.trimIndent()

        val CLEAR_OLD_RTDN_PAYLOADS =
            """
            UPDATE play_store_rtdn_events
            SET raw_payload = ''
            WHERE id IN (
                SELECT id FROM play_store_rtdn_events
                WHERE raw_payload <> ''
                  AND processing_status NOT IN ('received', 'retry')
                  AND created_at < now() - make_interval(secs => ?)
                LIMIT ?
            )
            """.trimIndent()
    }
}

data class DataRetentionSummary(
    val expiredMessages: Int,
    val deadSessions: Int,
    val passwordResetCodes: Int,
    val rtdnPayloads: Int,
) {
    val total: Int get() = expiredMessages + deadSessions + passwordResetCodes + rtdnPayloads
}

class DataRetentionWorker(
    private val service: DataRetentionService,
    private val intervalMillis: Long,
    private val jobLock: JobLock = UnlockedJobs,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(DataRetentionWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    runCatching {
                        jobLock.runExclusive("data-retention") {
                            val summary = service.runOnce()
                            if (summary.total > 0) {
                                logger.info(
                                    "Data retention removed expiredMessages={} deadSessions={} resetCodes={} rtdnPayloads={}",
                                    summary.expiredMessages,
                                    summary.deadSessions,
                                    summary.passwordResetCodes,
                                    summary.rtdnPayloads,
                                )
                            }
                        }
                    }.onFailure { error ->
                        logger.warn("Data retention failed", error)
                    }
                    delay(intervalMillis)
                }
            }
    }

    override fun close() {
        scope.cancel()
    }
}
