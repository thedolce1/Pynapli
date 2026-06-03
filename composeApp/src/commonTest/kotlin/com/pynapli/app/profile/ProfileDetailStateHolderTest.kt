package com.pynapli.app.profile

import com.pynapli.app.grid.GridCardTone
import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.app.media.MediaAlbumItemUi
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.BlockSummaryDto
import com.pynapli.shared.network.dto.HiddenProfileSummaryDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.ReportDto
import com.pynapli.shared.network.dto.RequestAlbumAccessResponseDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileDetailStateHolderTest {
    @Test
    fun sendSparkFromTonightTagsFlirtWithPlayingTonightSource() =
        runBlocking {
            val repository = RecordingPublicProfileRepository()
            val stateHolder = ProfileDetailStateHolder(repository)

            stateHolder.openFromTonight("user-1")
            stateHolder.sendSpark()

            assertEquals(listOf<Pair<String, String?>>("user-1" to "playing_tonight"), repository.flirtCalls)
        }

    @Test
    fun sendSparkFromNormalOpenLeavesSourceNull() =
        runBlocking {
            val repository = RecordingPublicProfileRepository()
            val stateHolder = ProfileDetailStateHolder(repository)

            stateHolder.open(sampleCard("user-2"))
            stateHolder.sendSpark()

            assertEquals(listOf<Pair<String, String?>>("user-2" to null), repository.flirtCalls)
        }

    @Test
    fun normalOpenAfterTonightOpenResetsSource() =
        runBlocking {
            val repository = RecordingPublicProfileRepository()
            val stateHolder = ProfileDetailStateHolder(repository)

            stateHolder.openFromTonight("user-tonight")
            stateHolder.open(sampleCard("user-grid"))
            stateHolder.sendSpark()

            assertEquals(listOf<Pair<String, String?>>("user-grid" to null), repository.flirtCalls)
        }
}

private fun sampleCard(userId: String): GridProfileCardUi =
    GridProfileCardUi(
        id = userId,
        displayName = "User $userId",
        thumbnailUrl = null,
        online = false,
        distanceLabel = null,
        tone = GridCardTone.entries.first(),
    )

private class RecordingPublicProfileRepository : PublicProfileRepository {
    val flirtCalls = mutableListOf<Pair<String, String?>>()

    override suspend fun loadProfile(userId: String): AppResult<PublicProfileDto> =
        AppResult.Success(
            PublicProfileDto(
                userId = userId,
                displayName = "User $userId",
                displayAge = 30,
                gender = "woman",
                orientation = "open",
                relationshipContext = "single",
                discoveryStyle = "both",
                allowGridMessages = true,
                allowFlirts = true,
                allowAlbumRequests = true,
            ),
        )

    override suspend fun sendFlirt(
        userId: String,
        source: String?,
    ): AppResult<SendFlirtResponseDto> {
        flirtCalls += userId to source
        return AppResult.Success(SendFlirtResponseDto(sent = true))
    }

    override suspend fun requestAlbumAccess(userId: String): AppResult<RequestAlbumAccessResponseDto> = AppResult.Success(RequestAlbumAccessResponseDto(requested = true))

    override suspend fun loadPrivateAlbum(userId: String): AppResult<List<MediaAlbumItemUi>> = AppResult.Success(emptyList())

    override suspend fun blockUser(
        userId: String,
        reason: String?,
    ): AppResult<BlockSummaryDto> = AppResult.Success(BlockSummaryDto(blockedUserId = userId, reason = reason, createdAt = "2026-06-03T00:00:00Z"))

    override suspend fun hideProfile(userId: String): AppResult<HiddenProfileSummaryDto> = AppResult.Success(HiddenProfileSummaryDto(hiddenUserId = userId, createdAt = "2026-06-03T00:00:00Z"))

    override suspend fun submitReport(
        userId: String,
        targetType: String,
        category: String,
        note: String?,
    ): AppResult<ReportDto> =
        AppResult.Success(
            ReportDto(
                reportId = "report-1",
                reportedUserId = userId,
                targetType = targetType,
                category = category,
                status = "open",
                createdAt = "2026-06-03T00:00:00Z",
            ),
        )
}
