package com.pynapli.server.messaging

import com.pynapli.shared.network.dto.CreateMessageMediaUploadRequestDto
import com.pynapli.shared.network.dto.MessageMediaUploadSlotDto
import java.util.UUID

/**
 * Narrow seam the messaging domain uses for chat photo media, so [MessagingService] stays
 * decoupled from the full media stack. The media module supplies the implementation
 * ([com.pynapli.server.media.MediaServiceMessageMediaGateway]); messaging never touches storage
 * signing, asset rows, or the moderation scanner directly.
 */
interface MessageMediaGateway {
    /** Opens an image-only upload slot for a one-off chat photo (not an album asset). */
    suspend fun createUploadSlot(
        ownerUserId: UUID,
        request: CreateMessageMediaUploadRequestDto,
    ): MessageMediaUploadSlotDto

    /**
     * Validates [mediaAssetId] is the sender's freshly uploaded chat photo, runs moderation, and
     * marks it ready/approved (or rejected). Returns its presentation facts, or null when the asset
     * is missing, not owned, the wrong kind, or rejected — in which case the send must be refused.
     */
    suspend fun prepareOutboundMedia(
        senderUserId: UUID,
        mediaAssetId: UUID,
    ): PreparedMessageMedia?

    /** Short-lived/local content URL for a message photo the caller is allowed to see. */
    fun contentUrl(mediaAssetId: UUID): String?
}

data class PreparedMessageMedia(
    val contentType: String,
    val pixelWidth: Int?,
    val pixelHeight: Int?,
)
