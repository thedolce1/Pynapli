package com.pynapli.server.activity

import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.media.MediaException
import com.pynapli.server.media.MediaService
import com.pynapli.server.safety.BlockRepository
import com.pynapli.shared.network.dto.ActivityEventDto
import com.pynapli.shared.network.dto.ActivityFeedResponseDto
import com.pynapli.shared.network.dto.RequestAlbumAccessResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto
import java.util.UUID
import kotlin.math.roundToInt

class ActivityService(
    private val repository: ActivityRepository,
    private val mediaService: MediaService? = null,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    private val blockRepository: BlockRepository? = null
) {
    suspend fun recordProfileView(viewerUserId: UUID, targetUserId: UUID) {
        repository.recordProfileView(viewerUserId, targetUserId)
    }

    suspend fun sendFlirt(senderUserId: UUID, targetUserId: UUID): SendFlirtResponseDto {
        if (senderUserId == targetUserId) throw ActivityException("INVALID_FLIRT_TARGET")
        if (!repository.recordFlirt(senderUserId, targetUserId)) {
            throw ActivityException("FLIRT_NOT_ALLOWED")
        }
        return SendFlirtResponseDto(sent = true)
    }

    suspend fun requestAlbumAccess(requesterUserId: UUID, targetUserId: UUID): RequestAlbumAccessResponseDto {
        if (requesterUserId == targetUserId) throw ActivityException("INVALID_ALBUM_REQUEST_TARGET")
        if (!repository.recordAlbumRequest(requesterUserId, targetUserId)) {
            throw ActivityException("ALBUM_REQUEST_NOT_ALLOWED")
        }
        val mediaRequested = runCatching {
            mediaService?.requestPrivateAlbumAccess(
                requesterUserId = requesterUserId,
                ownerUserId = targetUserId
            ) ?: true
        }.getOrElse { error ->
            if (error is MediaException) throw ActivityException(error.code)
            throw error
        }
        if (!mediaRequested) throw ActivityException("ALBUM_REQUEST_NOT_ALLOWED")
        return RequestAlbumAccessResponseDto(requested = true)
    }

    suspend fun respondToAlbumAccessRequest(
        ownerUserId: UUID,
        eventId: UUID,
        approve: Boolean
    ): RespondAlbumAccessResponseDto {
        val event = repository.getAlbumRequestEvent(ownerUserId, eventId)
            ?: throw ActivityException("ALBUM_REQUEST_NOT_FOUND")
        val status = mediaService?.decidePrivateAlbumAccessRequest(
            ownerUserId = event.ownerUserId,
            requesterUserId = event.requesterUserId,
            approved = approve
        ) ?: throw ActivityException("ALBUM_REQUEST_NOT_FOUND")
        return RespondAlbumAccessResponseDto(
            eventId = event.eventId.toString(),
            status = status
        )
    }

    suspend fun listActivity(recipientUserId: UUID, limit: Int = DEFAULT_LIMIT): ActivityFeedResponseDto {
        val normalizedLimit = limit.coerceIn(1, MAX_LIMIT)
        val events = repository.listActivity(recipientUserId, normalizedLimit)
        val blockedRelatedIds = blockRepository?.listBlockedRelatedUserIds(recipientUserId).orEmpty()
        val visible = if (blockedRelatedIds.isEmpty()) {
            events
        } else {
            events.filterNot { it.actorUserId in blockedRelatedIds }
        }
        return ActivityFeedResponseDto(
            items = visible.map { it.toDto() },
            nextCursor = null
        )
    }

    private fun ActivityEventRecord.toDto(): ActivityEventDto = ActivityEventDto(
        eventId = eventId.toString(),
        type = type,
        actorUserId = actorUserId.toString(),
        actorDisplayName = actorDisplayName,
        actorPhotoUrl = actorProfilePhotoMediaAssetId?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
        count = count,
        occurredAt = occurredAt.toString(),
        distanceLabel = if (showDistance) distanceMeters?.toDistanceLabel() else null,
        albumRequestStatus = albumRequestStatus
    )

    private fun Double.toDistanceLabel(): String {
        if (this < 1_000.0) {
            val roundedMeters = ((this / 100.0).roundToInt() * 100).coerceAtLeast(100)
            return "$roundedMeters m"
        }

        val km = this / 1_000.0
        return if (km < 10) {
            "%.1f km".format(km)
        } else {
            "${km.roundToInt()} km"
        }
    }

    companion object {
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 100
    }
}

class ActivityException(val code: String) : RuntimeException(code)
