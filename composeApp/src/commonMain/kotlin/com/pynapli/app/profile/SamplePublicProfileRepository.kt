package com.pynapli.app.profile

import com.pynapli.app.media.MediaAlbumItemUi
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.BlockSummaryDto
import com.pynapli.shared.network.dto.HiddenProfileSummaryDto
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.ReportDto
import com.pynapli.shared.network.dto.RequestAlbumAccessResponseDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto

class SamplePublicProfileRepository : PublicProfileRepository {
    override suspend fun loadProfile(userId: String): AppResult<PublicProfileDto> =
        AppResult.Success(
            PublicProfileDto(
                userId = userId,
                displayName = "Maya",
                displayAge = 35,
                gender = "woman",
                orientation = "open",
                relationshipContext = "linked",
                discoveryStyle = "both",
                distanceLabel = "1.2 km",
                distanceMeters = 1_200,
                linkedPartner = LinkedPartnerMiniDto(
                    userId = "sample-linked-1",
                    displayName = "Noa"
                ),
                allowGridMessages = true,
                allowFlirts = true,
                allowAlbumRequests = true
            )
        )

    override suspend fun sendFlirt(userId: String): AppResult<SendFlirtResponseDto> =
        AppResult.Success(SendFlirtResponseDto(sent = true))

    override suspend fun requestAlbumAccess(userId: String): AppResult<RequestAlbumAccessResponseDto> =
        AppResult.Success(RequestAlbumAccessResponseDto(requested = true))

    override suspend fun loadPrivateAlbum(userId: String): AppResult<List<MediaAlbumItemUi>> =
        AppResult.Success(emptyList())

    override suspend fun blockUser(userId: String, reason: String?): AppResult<BlockSummaryDto> =
        AppResult.Success(
            BlockSummaryDto(
                blockedUserId = userId,
                reason = reason,
                createdAt = "2026-04-28T00:00:00Z"
            )
        )

    override suspend fun hideProfile(userId: String): AppResult<HiddenProfileSummaryDto> =
        AppResult.Success(
            HiddenProfileSummaryDto(
                hiddenUserId = userId,
                createdAt = "2026-05-26T00:00:00Z"
            )
        )

    override suspend fun submitReport(
        userId: String,
        targetType: String,
        category: String,
        note: String?
    ): AppResult<ReportDto> =
        AppResult.Success(
            ReportDto(
                reportId = "sample-report-1",
                reportedUserId = userId,
                targetType = targetType,
                category = category,
                status = "open",
                createdAt = "2026-04-28T00:00:00Z"
            )
        )
}
