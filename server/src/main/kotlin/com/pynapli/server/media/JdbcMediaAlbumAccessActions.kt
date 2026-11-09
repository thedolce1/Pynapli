package com.pynapli.server.media

import com.pynapli.server.db.inTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.requestMediaAlbumAccess(
    ownerUserId: UUID,
    requesterUserId: UUID,
    requestIdFactory: () -> UUID,
): MediaAlbumAccessRequestRecord? =
    withContext(Dispatchers.IO) {
        if (ownerUserId == requesterUserId) return@withContext null

        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    WITH album AS (
                        INSERT INTO media_albums (id, owner_user_id, kind, title)
                        VALUES (?, ?, 'private', 'Private album')
                        ON CONFLICT (owner_user_id, kind) WHERE deleted_at IS NULL
                        DO UPDATE SET updated_at = media_albums.updated_at
                        RETURNING id, owner_user_id
                    ),
                    pending AS (
                        SELECT r.id,
                               r.album_id,
                               r.owner_user_id,
                               r.requester_user_id,
                               r.status,
                               r.requested_at,
                               r.decided_at,
                               r.decided_by_user_id
                        FROM media_album_access_requests r
                        JOIN album a ON a.id = r.album_id
                        WHERE r.requester_user_id = ?
                          AND r.status = 'pending'
                        LIMIT 1
                    ),
                    inserted AS (
                        INSERT INTO media_album_access_requests (id, album_id, owner_user_id, requester_user_id)
                        SELECT ?, a.id, a.owner_user_id, ?
                        FROM album a
                        WHERE NOT EXISTS (SELECT 1 FROM pending)
                        RETURNING id,
                                  album_id,
                                  owner_user_id,
                                  requester_user_id,
                                  status,
                                  requested_at,
                                  decided_at,
                                  decided_by_user_id
                    )
                    SELECT * FROM inserted
                    UNION ALL
                    SELECT * FROM pending
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, ownerUserId)
                    statement.setObject(3, requesterUserId)
                    statement.setObject(4, requestIdFactory())
                    statement.setObject(5, requesterUserId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.toMediaAlbumAccessRequestRecord() else null
                    }
                }
        }
    }

internal suspend fun DataSource.decideMediaAlbumAccessRequest(
    ownerUserId: UUID,
    requesterUserId: UUID,
    approved: Boolean,
    decidedByUserId: UUID,
): MediaAlbumAccessRequestRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val request = connection.updatePendingAlbumAccessRequest(ownerUserId, requesterUserId, approved, decidedByUserId)

                if (request == null) {
                    connection.rollback()
                    return@withContext connection.findLatestDecidedAlbumAccessRequest(ownerUserId, requesterUserId)
                }

                if (approved) {
                    connection.upsertAlbumAccessGrant(
                        albumId = request.albumId,
                        ownerUserId = request.ownerUserId,
                        granteeUserId = request.requesterUserId,
                        grantedByUserId = decidedByUserId,
                    )
                }

                connection.commit()
                request
            }
        }
    }

internal suspend fun DataSource.hasMediaAlbumAccess(
    viewerUserId: UUID,
    albumId: UUID,
): Boolean =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.hasAlbumAccess(viewerUserId, albumId)
        }
    }

internal fun Connection.hasAlbumAccess(
    viewerUserId: UUID,
    albumId: UUID,
): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1
            FROM media_albums a
            WHERE a.id = ?
              AND a.deleted_at IS NULL
              AND (
                  a.owner_user_id = ?
                  OR (
                      a.visibility_status = 'visible'
                      AND a.kind = 'profile'
                  )
                  OR (
                      a.visibility_status = 'visible'
                      AND a.kind = 'private'
                      AND EXISTS (
                          SELECT 1
                          FROM media_album_access_grants g
                          WHERE g.album_id = a.id
                            AND g.grantee_user_id = ?
                            AND g.revoked_at IS NULL
                      )
                  )
              )
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, albumId)
        statement.setObject(2, viewerUserId)
        statement.setObject(3, viewerUserId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

private fun Connection.updatePendingAlbumAccessRequest(
    ownerUserId: UUID,
    requesterUserId: UUID,
    approved: Boolean,
    decidedByUserId: UUID,
): MediaAlbumAccessRequestRecord? =
    prepareStatement(
        """
        UPDATE media_album_access_requests
        SET status = ?,
            decided_at = now(),
            decided_by_user_id = ?
        WHERE id = (
            SELECT r.id
            FROM media_album_access_requests r
            JOIN media_albums a
              ON a.id = r.album_id
             AND a.owner_user_id = r.owner_user_id
             AND a.kind = 'private'
             AND a.deleted_at IS NULL
            WHERE r.owner_user_id = ?
              AND r.requester_user_id = ?
              AND r.status = 'pending'
            ORDER BY r.requested_at DESC, r.id DESC
            LIMIT 1
        )
        RETURNING id,
                  album_id,
                  owner_user_id,
                  requester_user_id,
                  status,
                  requested_at,
                  decided_at,
                  decided_by_user_id
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, if (approved) "approved" else "denied")
        statement.setObject(2, decidedByUserId)
        statement.setObject(3, ownerUserId)
        statement.setObject(4, requesterUserId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toMediaAlbumAccessRequestRecord() else null
        }
    }

private fun Connection.findLatestDecidedAlbumAccessRequest(
    ownerUserId: UUID,
    requesterUserId: UUID,
): MediaAlbumAccessRequestRecord? =
    prepareStatement(
        """
        SELECT id,
               album_id,
               owner_user_id,
               requester_user_id,
               status,
               requested_at,
               decided_at,
               decided_by_user_id
        FROM media_album_access_requests
        WHERE owner_user_id = ?
          AND requester_user_id = ?
          AND status IN ('approved', 'denied')
        ORDER BY decided_at DESC NULLS LAST, id DESC
        LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, ownerUserId)
        statement.setObject(2, requesterUserId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toMediaAlbumAccessRequestRecord() else null
        }
    }
