package com.pynapli.app.profile

import com.pynapli.app.media.MediaAlbumItemUi
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.BlockSummaryDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.ReportDto
import com.pynapli.shared.network.dto.RequestAlbumAccessResponseDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto

interface PublicProfileRepository {
    suspend fun loadProfile(userId: String): AppResult<PublicProfileDto>

    suspend fun sendFlirt(userId: String): AppResult<SendFlirtResponseDto>

    suspend fun requestAlbumAccess(userId: String): AppResult<RequestAlbumAccessResponseDto>

    suspend fun loadPrivateAlbum(userId: String): AppResult<List<MediaAlbumItemUi>>

    suspend fun blockUser(userId: String, reason: String?): AppResult<BlockSummaryDto>

    suspend fun submitReport(
        userId: String,
        targetType: String,
        category: String,
        note: String?
    ): AppResult<ReportDto>
}
