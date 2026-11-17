package com.pynapli.app.profile

import com.pynapli.app.media.MediaAlbumItemUi
import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.map
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.BlockSummaryDto
import com.pynapli.shared.network.dto.BlockUserRequestDto
import com.pynapli.shared.network.dto.CreateReportRequestDto
import com.pynapli.shared.network.dto.HiddenProfileSummaryDto
import com.pynapli.shared.network.dto.MediaAlbumItemDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.ReportDto
import com.pynapli.shared.network.dto.RequestAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RequestAlbumAccessResponseDto
import com.pynapli.shared.network.dto.SendFlirtRequestDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto

class ApiPublicProfileRepository(
    private val apiClient: PynapliApiClient,
) : PublicProfileRepository {
    override suspend fun loadProfile(userId: String): AppResult<PublicProfileDto> = apiClient.getPublicProfile(userId)

    override suspend fun sendFlirt(
        userId: String,
        source: String?,
    ): AppResult<SendFlirtResponseDto> = apiClient.sendFlirt(SendFlirtRequestDto(targetUserId = userId, source = source))

    override suspend fun requestAlbumAccess(userId: String): AppResult<RequestAlbumAccessResponseDto> = apiClient.requestAlbumAccess(RequestAlbumAccessRequestDto(targetUserId = userId))

    override suspend fun loadPrivateAlbum(userId: String): AppResult<List<MediaAlbumItemUi>> =
        apiClient.listUserPrivateAlbum(userId).map { value ->
            value.items.map { item ->
                item.toPrivateAlbumUi(
                    mediaUrl = apiClient.mediaAssetContentUrl(item.mediaAsset.mediaAssetId),
                )
            }
        }

    override suspend fun blockUser(
        userId: String,
        reason: String?,
    ): AppResult<BlockSummaryDto> = apiClient.blockUser(userId, BlockUserRequestDto(reason = reason))

    override suspend fun hideProfile(userId: String): AppResult<HiddenProfileSummaryDto> = apiClient.hideProfile(userId)

    override suspend fun submitReport(
        userId: String,
        targetType: String,
        category: String,
        note: String?,
        targetId: String?,
    ): AppResult<ReportDto> =
        apiClient.submitReport(
            CreateReportRequestDto(
                reportedUserId = userId,
                targetType = targetType,
                targetId = targetId,
                category = category,
                note = note,
            ),
        )
}

private fun MediaAlbumItemDto.toPrivateAlbumUi(mediaUrl: String? = null): MediaAlbumItemUi =
    MediaAlbumItemUi(
        mediaAssetId = mediaAsset.mediaAssetId,
        albumId = albumId,
        albumKind = MediaAlbumKindUi.Private,
        mediaType = mediaAsset.mediaType,
        contentType = mediaAsset.contentType,
        processingStatus = mediaAsset.processingStatus,
        moderationStatus = mediaAsset.moderationStatus,
        sortOrder = sortOrder,
        createdAt = addedAt,
        mediaUrl = mediaUrl,
    )
