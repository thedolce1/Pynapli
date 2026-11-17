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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileDetailStateHolderTest {
    @Test
    fun sendSparkFromTonightTagsFlirtWithPlayingTonightSource() =
        runTest {
            val repository = RecordingPublicProfileRepository()
            val stateHolder = ProfileDetailStateHolder(repository)

            stateHolder.openFromTonight("user-1")
            stateHolder.sendSpark()

            assertEquals(listOf<Pair<String, String?>>("user-1" to "playing_tonight"), repository.flirtCalls)
        }

    @Test
    fun aReportIsConfirmedThenTheCardLeavesWhenTheSheetCloses() =
        runTest {
            val stateHolder = ProfileDetailStateHolder(RecordingPublicProfileRepository())
            stateHolder.open(sampleCard("user-r"))

            stateHolder.submitReport("spam_or_fake_profile")
            // The confirmation stays readable while the sheet is open.
            assertEquals(ProfileDetailStateHolder.REPORT_CONFIRMATION, stateHolder.state.value.reportFeedback)
            assertEquals(null, stateHolder.state.value.hiddenTargetUserId)

            // Closing hands them to the hide path every surface already handles.
            stateHolder.dismiss()
            assertEquals("user-r", stateHolder.state.value.hiddenTargetUserId)
            assertEquals(false, stateHolder.state.value.visible)
        }

    @Test
    fun reportingAnAlbumSendsItsIdAndKeepsThePersonVisible() =
        runTest {
            val repository = RecordingPublicProfileRepository()
            val stateHolder = ProfileDetailStateHolder(repository)
            stateHolder.open(sampleCard("user-a"))

            stateHolder.submitAlbumReport("album-9", "non_consensual_content")

            assertEquals(listOf(Triple<String, String, String?>("user-a", "album", "album-9")), repository.reportCalls)
            assertEquals(ProfileDetailStateHolder.ALBUM_REPORT_CONFIRMATION, stateHolder.state.value.reportFeedback)
            stateHolder.dismiss()
            assertEquals<String?>(null, stateHolder.state.value.hiddenTargetUserId)
        }

    @Test
    fun sendSparkFromNormalOpenLeavesSourceNull() =
        runTest {
            val repository = RecordingPublicProfileRepository()
            val stateHolder = ProfileDetailStateHolder(repository)

            stateHolder.open(sampleCard("user-2"))
            stateHolder.sendSpark()

            assertEquals(listOf<Pair<String, String?>>("user-2" to null), repository.flirtCalls)
        }

    @Test
    fun normalOpenAfterTonightOpenResetsSource() =
        runTest {
            val repository = RecordingPublicProfileRepository()
            val stateHolder = ProfileDetailStateHolder(repository)

            stateHolder.openFromTonight("user-tonight")
            stateHolder.open(sampleCard("user-grid"))
            stateHolder.sendSpark()

            assertEquals(listOf<Pair<String, String?>>("user-grid" to null), repository.flirtCalls)
        }

    @Test
    fun openOwnPreviewLoadsProfileInReadOnlyMode() =
        runTest {
            val repository = RecordingPublicProfileRepository()
            val stateHolder = ProfileDetailStateHolder(repository)

            stateHolder.openOwnPreview(userId = "me-1", displayName = "Seb", profilePhotoUrl = null)

            val state = stateHolder.state.value
            assertEquals(true, state.isOwnProfile)
            assertEquals("me-1", state.profile?.userId)
            assertEquals("Seb", state.selectedCard?.displayName)
        }

    @Test
    fun normalOpenAfterOwnPreviewClearsOwnProfileFlag() =
        runTest {
            val repository = RecordingPublicProfileRepository()
            val stateHolder = ProfileDetailStateHolder(repository)

            stateHolder.openOwnPreview(userId = "me-1", displayName = "Seb", profilePhotoUrl = null)
            stateHolder.open(sampleCard("user-2"))

            assertEquals(false, stateHolder.state.value.isOwnProfile)
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
    val reportCalls = mutableListOf<Triple<String, String, String?>>()

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
        targetId: String?,
    ): AppResult<ReportDto> {
        reportCalls += Triple(userId, targetType, targetId)
        return AppResult.Success(
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
}
