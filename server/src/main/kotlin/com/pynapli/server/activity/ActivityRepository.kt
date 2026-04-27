package com.pynapli.server.activity

import java.time.Instant
import java.util.UUID

interface ActivityRepository {
    suspend fun recordProfileView(viewerUserId: UUID, targetUserId: UUID)

    suspend fun recordFlirt(senderUserId: UUID, targetUserId: UUID): Boolean

    suspend fun recordAlbumRequest(requesterUserId: UUID, targetUserId: UUID): Boolean

    suspend fun getAlbumRequestEvent(recipientUserId: UUID, eventId: UUID): AlbumRequestEventRecord?

    suspend fun listActivity(recipientUserId: UUID, limit: Int): List<ActivityEventRecord>
}

data class ActivityEventRecord(
    val eventId: UUID,
    val type: String,
    val actorUserId: UUID,
    val actorDisplayName: String,
    val actorProfilePhotoMediaAssetId: UUID?,
    val count: Int,
    val occurredAt: Instant,
    val showDistance: Boolean,
    val distanceMeters: Double?,
    val albumRequestStatus: String?
)

data class AlbumRequestEventRecord(
    val eventId: UUID,
    val ownerUserId: UUID,
    val requesterUserId: UUID
)
