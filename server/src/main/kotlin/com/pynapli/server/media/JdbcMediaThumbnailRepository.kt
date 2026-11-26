package com.pynapli.server.media

import com.pynapli.server.db.withConnection
import java.util.UUID
import javax.sql.DataSource

/**
 * Thumbnail bookkeeping on `media_assets` (V130). Deliberately separate from [MediaAssetRecord] and
 * its row mapper: no other query has to learn these columns.
 *
 * Every write that can make a thumbnail public re-checks [ELIGIBLE] in the same statement, so a photo
 * that was held, rejected, removed, deleted or taken out of the profile album meanwhile never gets
 * (or keeps) a ready thumbnail. Private-album, chat and verification media are never eligible: they
 * are not `intended_album_kind = 'profile'` items of a live profile album.
 */
interface MediaThumbnailRepository {
    /**
     * Atomically claims [mediaAssetId] for generation when it is eligible, has no ready thumbnail, no
     * terminal failure and no live lease. Records [variant] first, so a crash after the upload still
     * leaves a row the reconciler cleans. Null when there is nothing to do.
     */
    suspend fun claim(
        mediaAssetId: UUID,
        variant: String,
    ): ThumbnailSource?

    /** Marks the claimed thumbnail ready only if the asset is STILL eligible. False = delete what you wrote. */
    suspend fun markReady(
        mediaAssetId: UUID,
        variant: String,
    ): Boolean

    /** A transient failure: release the lease; after [maxAttempts] it becomes terminal. */
    suspend fun markAttemptFailed(
        mediaAssetId: UUID,
        maxAttempts: Int,
    )

    /** Terminal: this original can't be made into a thumbnail. Clears the variant (nothing was written). */
    suspend fun markUnrenderable(mediaAssetId: UUID)

    /** Eligible assets still without a ready thumbnail (backfill), oldest first. */
    suspend fun listMissing(limit: Int): List<UUID>

    /** Assets that have (or may have) a thumbnail object, by id after [afterId] (reconciler walk). */
    suspend fun listWithVariant(
        afterId: UUID?,
        limit: Int,
    ): List<ThumbnailPresence>

    /** Forgets the thumbnail of an asset that is no longer eligible. Only after its object is gone. */
    suspend fun clearVariant(mediaAssetId: UUID)

    /** Of [mediaAssetIds], the ones whose [variant] is ready and that are still eligible to show. */
    suspend fun readyAmong(
        mediaAssetIds: Collection<UUID>,
        variant: String,
    ): Set<UUID>
}

data class ThumbnailPresence(
    val mediaAssetId: UUID,
    val storageProvider: String,
    val storageBucket: String,
    val eligible: Boolean,
    // A generator holds the lease right now: it cleans up after itself, so the reconciler waits.
    val leaseLive: Boolean,
)

class JdbcMediaThumbnailRepository(
    private val dataSource: DataSource,
    // A generator that holds a claim longer than this is presumed dead.
    private val leaseSeconds: Int = 300,
) : MediaThumbnailRepository {
    override suspend fun claim(
        mediaAssetId: UUID,
        variant: String,
    ): ThumbnailSource? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE media_assets m
                    SET thumbnail_variant = ?,
                        thumbnail_claimed_at = now()
                    WHERE m.id = ?
                      AND m.thumbnail_ready_at IS NULL
                      AND m.thumbnail_failed_at IS NULL
                      AND (m.thumbnail_claimed_at IS NULL OR m.thumbnail_claimed_at < now() - (?::integer * interval '1 second'))
                      AND $ELIGIBLE
                    RETURNING m.id, m.storage_provider, m.storage_bucket, m.storage_object_key, m.byte_size
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, variant)
                    statement.setObject(2, mediaAssetId)
                    statement.setInt(3, leaseSeconds)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            ThumbnailSource(
                                mediaAssetId = rs.getObject("id", UUID::class.java),
                                storageProvider = rs.getString("storage_provider"),
                                storageBucket = rs.getString("storage_bucket"),
                                storageObjectKey = rs.getString("storage_object_key"),
                                byteSize = rs.getLong("byte_size"),
                            )
                        }
                    }
                }
        }

    override suspend fun markReady(
        mediaAssetId: UUID,
        variant: String,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE media_assets m
                    SET thumbnail_ready_at = now(),
                        thumbnail_claimed_at = NULL,
                        thumbnail_attempts = 0
                    WHERE m.id = ?
                      AND m.thumbnail_variant = ?
                      AND $ELIGIBLE
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, mediaAssetId)
                    statement.setString(2, variant)
                    statement.executeUpdate() == 1
                }
        }

    override suspend fun markAttemptFailed(
        mediaAssetId: UUID,
        maxAttempts: Int,
    ) {
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE media_assets
                    SET thumbnail_attempts = thumbnail_attempts + 1,
                        thumbnail_claimed_at = NULL,
                        thumbnail_failed_at = CASE WHEN thumbnail_attempts + 1 >= ? THEN now() ELSE thumbnail_failed_at END
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, maxAttempts)
                    statement.setObject(2, mediaAssetId)
                    statement.executeUpdate()
                }
        }
    }

    override suspend fun markUnrenderable(mediaAssetId: UUID) {
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE media_assets
                    SET thumbnail_failed_at = now(),
                        thumbnail_claimed_at = NULL,
                        thumbnail_variant = NULL,
                        thumbnail_ready_at = NULL
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, mediaAssetId)
                    statement.executeUpdate()
                }
        }
    }

    override suspend fun listMissing(limit: Int): List<UUID> =
        dataSource.withConnection { connection ->
            // The leading predicates match idx_media_assets_thumbnail_backfill.
            connection
                .prepareStatement(
                    """
                    SELECT m.id
                    FROM media_assets m
                    WHERE m.thumbnail_ready_at IS NULL
                      AND m.thumbnail_failed_at IS NULL
                      AND (m.thumbnail_claimed_at IS NULL OR m.thumbnail_claimed_at < now() - (?::integer * interval '1 second'))
                      AND $ELIGIBLE
                    ORDER BY m.created_at, m.id
                    LIMIT ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, leaseSeconds)
                    statement.setInt(2, limit)
                    statement.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.getObject("id", UUID::class.java)) }
                    }
                }
        }

    override suspend fun listWithVariant(
        afterId: UUID?,
        limit: Int,
    ): List<ThumbnailPresence> =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT m.id,
                           m.storage_provider,
                           m.storage_bucket,
                           ($ELIGIBLE) AS eligible,
                           (m.thumbnail_claimed_at IS NOT NULL
                               AND m.thumbnail_claimed_at >= now() - (?::integer * interval '1 second')) AS lease_live
                    FROM media_assets m
                    WHERE m.thumbnail_variant IS NOT NULL
                      AND (?::uuid IS NULL OR m.id > ?::uuid)
                    ORDER BY m.id
                    LIMIT ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, leaseSeconds)
                    statement.setObject(2, afterId)
                    statement.setObject(3, afterId)
                    statement.setInt(4, limit)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    ThumbnailPresence(
                                        mediaAssetId = rs.getObject("id", UUID::class.java),
                                        storageProvider = rs.getString("storage_provider"),
                                        storageBucket = rs.getString("storage_bucket"),
                                        eligible = rs.getBoolean("eligible"),
                                        leaseLive = rs.getBoolean("lease_live"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    override suspend fun clearVariant(mediaAssetId: UUID) {
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE media_assets
                    SET thumbnail_variant = NULL,
                        thumbnail_ready_at = NULL,
                        thumbnail_claimed_at = NULL
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, mediaAssetId)
                    statement.executeUpdate()
                }
        }
    }

    override suspend fun readyAmong(
        mediaAssetIds: Collection<UUID>,
        variant: String,
    ): Set<UUID> {
        if (mediaAssetIds.isEmpty()) return emptySet()
        return dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT m.id
                    FROM media_assets m
                    WHERE m.id = ANY(?::uuid[])
                      AND m.thumbnail_variant = ?
                      AND m.thumbnail_ready_at IS NOT NULL
                      -- Defence in depth: callers pass ids their own photo projection already
                      -- gated; a thumbnail URL still needs the photo to be showable right now.
                      AND $ELIGIBLE
                    """.trimIndent(),
                ).use { statement ->
                    statement.setArray(1, connection.createArrayOf("uuid", mediaAssetIds.toTypedArray()))
                    statement.setString(2, variant)
                    statement.executeQuery().use { rs ->
                        buildSet { while (rs.next()) add(rs.getObject("id", UUID::class.java)) }
                    }
                }
        }
    }

    companion object {
        /**
         * An approved, ready, live public profile image in a live profile album: the photos the Grid,
         * Swipe and public profile may show (same definition as their photo projections). Alias `m`.
         */
        internal val ELIGIBLE =
            """
            m.deleted_at IS NULL
                      AND m.media_type = 'image'
                      AND m.processing_status = 'ready'
                      AND m.moderation_status = 'approved'
                      AND m.intended_album_kind = 'profile'
                      AND EXISTS (
                          SELECT 1
                          FROM media_album_items thumb_mai
                          JOIN media_albums thumb_ma
                            ON thumb_ma.id = thumb_mai.album_id
                           AND thumb_ma.owner_user_id = thumb_mai.owner_user_id
                          WHERE thumb_mai.media_asset_id = m.id
                            AND thumb_mai.owner_user_id = m.owner_user_id
                            AND thumb_mai.removed_at IS NULL
                            AND thumb_ma.kind = 'profile'
                            AND thumb_ma.deleted_at IS NULL
                      )
            """.trimIndent()
    }
}
