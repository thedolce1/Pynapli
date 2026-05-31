package com.pynapli.server.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class JdbcMediaCleanupRepository(
    private val dataSource: DataSource,
) : MediaCleanupRepository {
    override suspend fun purgeStaleAssets(
        abandonedBefore: Instant,
        rejectedBefore: Instant,
        softDeletedBefore: Instant,
    ): MediaCleanupSummary =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                // media_album_items cascade on asset delete (FK ON DELETE CASCADE), so deleting an
                // asset also drops it from any album in the same statement.
                val abandoned = connection.deleteCount(DELETE_ABANDONED, abandonedBefore)
                val rejected = connection.deleteCount(DELETE_REJECTED, rejectedBefore)
                val softDeleted = connection.deleteCount(DELETE_SOFT_DELETED, softDeletedBefore)
                MediaCleanupSummary(
                    abandonedUploads = abandoned,
                    rejectedAssets = rejected,
                    softDeletedAssets = softDeleted,
                )
            }
        }

    private fun Connection.deleteCount(
        sql: String,
        before: Instant,
    ): Int =
        prepareStatement(sql).use { statement ->
            statement.setTimestamp(1, Timestamp.from(before))
            statement.executeUpdate()
        }

    companion object {
        private val DELETE_ABANDONED =
            """
            DELETE FROM media_assets
            WHERE processing_status = 'pending_upload'
              AND deleted_at IS NULL
              AND created_at < ?
            """.trimIndent()

        private val DELETE_REJECTED =
            """
            DELETE FROM media_assets
            WHERE moderation_status = 'rejected'
              AND updated_at < ?
            """.trimIndent()

        private val DELETE_SOFT_DELETED =
            """
            DELETE FROM media_assets
            WHERE deleted_at IS NOT NULL
              AND deleted_at < ?
            """.trimIndent()
    }
}
