package com.pynapli.app.profilehub

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.AuthUserDto
import com.pynapli.shared.network.dto.BlockSummaryDto
import com.pynapli.shared.network.dto.BlocksListDto
import com.pynapli.shared.network.dto.DiscoverySettingsDto
import com.pynapli.shared.network.dto.HiddenProfileSummaryDto
import com.pynapli.shared.network.dto.HiddenProfilesListDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.ProfileDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileHubStateHolderTest {
    @Test
    fun loadKeepsProfileVisibleWhenAgeStatusUnavailable() = runBlocking {
        val repository = FakeProfileHubRepository(
            loadAgeResult = AppResult.Failure(AppError("NETWORK_ERROR", "Offline"))
        )
        val stateHolder = ProfileHubStateHolder(repository)

        stateHolder.load()

        assertEquals("Maya", stateHolder.state.value.me?.profile?.displayName)
        assertNull(stateHolder.state.value.ageVerificationStatus)
        assertNull(stateHolder.state.value.errorMessage)
    }

    @Test
    fun startAgeVerificationSendsAdultConfirmationAndShowsFeedback() = runBlocking {
        val repository = FakeProfileHubRepository(
            startAgeResult = AppResult.Success(
                AgeVerificationStatusDto(
                    status = "pending",
                    method = "manual_review",
                    provider = "internal_manual",
                    requestedAt = "2026-05-12T12:00:00Z",
                    message = "Age verification is pending review."
                )
            )
        )
        val stateHolder = ProfileHubStateHolder(repository)
        stateHolder.load()
        stateHolder.openAgeVerification()

        stateHolder.startAgeVerification()

        assertEquals(true, repository.lastStartRequest?.confirmedAdult)
        assertEquals("pending", stateHolder.state.value.ageVerificationStatus?.status)
        assertEquals(false, stateHolder.state.value.ageVerificationDialogVisible)
        assertEquals("Age verification is pending review.", stateHolder.state.value.feedbackMessage)
    }

    @Test
    fun failedAgeVerificationStartKeepsDialogOpenWithError() = runBlocking {
        val repository = FakeProfileHubRepository(
            startAgeResult = AppResult.Failure(AppError("BIRTHDATE_REQUIRED", "Add your birthdate."))
        )
        val stateHolder = ProfileHubStateHolder(repository)
        stateHolder.load()
        stateHolder.openAgeVerification()

        stateHolder.startAgeVerification()

        assertEquals(true, stateHolder.state.value.ageVerificationDialogVisible)
        assertEquals("Add your birthdate.", stateHolder.state.value.errorMessage)
    }

    @Test
    fun loadBlocksShowsSafetyCenterBlockList() = runBlocking {
        val repository = FakeProfileHubRepository(
            listBlocksResult = AppResult.Success(
                BlocksListDto(
                    blocks = listOf(
                        BlockSummaryDto(
                            blockedUserId = "blocked-1",
                            blockedDisplayName = "Jordan",
                            createdAt = "2026-05-26T12:00:00Z"
                        )
                    )
                )
            )
        )
        val stateHolder = ProfileHubStateHolder(repository)

        stateHolder.openSafetyCenter()
        stateHolder.loadBlocks()

        assertEquals(true, stateHolder.state.value.safetyCenterVisible)
        assertEquals("Jordan", stateHolder.state.value.blockedUsers.single().blockedDisplayName)
        assertEquals(null, stateHolder.state.value.errorMessage)
    }

    @Test
    fun unblockUserRemovesBlockedUserAndShowsFeedback() = runBlocking {
        val repository = FakeProfileHubRepository(
            listBlocksResult = AppResult.Success(
                BlocksListDto(
                    blocks = listOf(
                        BlockSummaryDto(
                            blockedUserId = "blocked-1",
                            blockedDisplayName = "Jordan",
                            createdAt = "2026-05-26T12:00:00Z"
                        )
                    )
                )
            ),
            unblockResult = AppResult.Success(Unit)
        )
        val stateHolder = ProfileHubStateHolder(repository)
        stateHolder.openSafetyCenter()
        stateHolder.loadBlocks()

        stateHolder.unblockUser("blocked-1")

        assertEquals("blocked-1", repository.lastUnblockedUserId)
        assertEquals(emptyList(), stateHolder.state.value.blockedUsers)
        assertEquals("User unblocked", stateHolder.state.value.feedbackMessage)
    }

    @Test
    fun loadHiddenProfilesShowsSafetyCenterHiddenList() = runBlocking {
        val repository = FakeProfileHubRepository(
            listHiddenProfilesResult = AppResult.Success(
                HiddenProfilesListDto(
                    hiddenProfiles = listOf(
                        HiddenProfileSummaryDto(
                            hiddenUserId = "hidden-1",
                            hiddenDisplayName = "Riley",
                            createdAt = "2026-05-26T12:00:00Z"
                        )
                    )
                )
            )
        )
        val stateHolder = ProfileHubStateHolder(repository)

        stateHolder.openSafetyCenter()
        stateHolder.loadHiddenProfiles()

        assertEquals(true, stateHolder.state.value.safetyCenterVisible)
        assertEquals("Riley", stateHolder.state.value.hiddenProfiles.single().hiddenDisplayName)
        assertEquals(null, stateHolder.state.value.errorMessage)
    }

    @Test
    fun unhideProfileRemovesHiddenProfileAndShowsFeedback() = runBlocking {
        val repository = FakeProfileHubRepository(
            listHiddenProfilesResult = AppResult.Success(
                HiddenProfilesListDto(
                    hiddenProfiles = listOf(
                        HiddenProfileSummaryDto(
                            hiddenUserId = "hidden-1",
                            hiddenDisplayName = "Riley",
                            createdAt = "2026-05-26T12:00:00Z"
                        )
                    )
                )
            ),
            unhideResult = AppResult.Success(Unit)
        )
        val stateHolder = ProfileHubStateHolder(repository)
        stateHolder.openSafetyCenter()
        stateHolder.loadHiddenProfiles()

        stateHolder.unhideProfile("hidden-1")

        assertEquals("hidden-1", repository.lastUnhiddenUserId)
        assertEquals(emptyList(), stateHolder.state.value.hiddenProfiles)
        assertEquals("Profile unhidden", stateHolder.state.value.feedbackMessage)
    }
}

private class FakeProfileHubRepository(
    private val loadMeResult: AppResult<MeDto> = AppResult.Success(testMe()),
    private val loadAgeResult: AppResult<AgeVerificationStatusDto> = AppResult.Success(
        AgeVerificationStatusDto(status = "not_started", message = "Age verification has not started.")
    ),
    private val startAgeResult: AppResult<AgeVerificationStatusDto> = AppResult.Failure(
        AppError("UNIMPLEMENTED", "Not implemented.")
    ),
    private val listBlocksResult: AppResult<BlocksListDto> = AppResult.Success(BlocksListDto(emptyList())),
    private val unblockResult: AppResult<Unit> = AppResult.Failure(
        AppError("UNIMPLEMENTED", "Not implemented.")
    ),
    private val listHiddenProfilesResult: AppResult<HiddenProfilesListDto> = AppResult.Success(
        HiddenProfilesListDto(emptyList())
    ),
    private val unhideResult: AppResult<Unit> = AppResult.Failure(
        AppError("UNIMPLEMENTED", "Not implemented.")
    )
) : ProfileHubRepository {
    var lastStartRequest: StartAgeVerificationRequestDto? = null
        private set
    var lastUnblockedUserId: String? = null
        private set
    var lastUnhiddenUserId: String? = null
        private set

    override suspend fun loadMe(): AppResult<MeDto> = loadMeResult

    override suspend fun loadAgeVerificationStatus(): AppResult<AgeVerificationStatusDto> = loadAgeResult

    override suspend fun startAgeVerification(
        request: StartAgeVerificationRequestDto
    ): AppResult<AgeVerificationStatusDto> {
        lastStartRequest = request
        return startAgeResult
    }

    override suspend fun updateProfile(request: UpdateProfileRequestDto): AppResult<MeDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun updateDiscoverySettings(request: UpdateDiscoverySettingsRequestDto): AppResult<MeDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listBlocks(): AppResult<BlocksListDto> = listBlocksResult

    override suspend fun unblockUser(userId: String): AppResult<Unit> {
        lastUnblockedUserId = userId
        return unblockResult
    }

    override suspend fun listHiddenProfiles(): AppResult<HiddenProfilesListDto> = listHiddenProfilesResult

    override suspend fun unhideProfile(userId: String): AppResult<Unit> {
        lastUnhiddenUserId = userId
        return unhideResult
    }
}

private fun testMe(): MeDto =
    MeDto(
        user = AuthUserDto(userId = "user-1", status = "active"),
        profile = ProfileDto(
            userId = "user-1",
            displayName = "Maya",
            birthdate = "1990-01-01",
            displayAge = 36,
            gender = "woman",
            orientation = "open",
            relationshipContext = "linked",
            discoveryStyle = "both",
            profileCompletenessScore = 100,
            moderationStatus = "approved",
            visibilityStatus = "visible",
            ageVerified = false
        ),
        discoverySettings = DiscoverySettingsDto(
            gridVisible = true,
            swipeVisible = true,
            eventsVisible = true,
            showDistance = true,
            showInViewedLists = true,
            incognito = false,
            allowGridMessages = true,
            gridMessagePolicy = "everyone",
            allowFlirts = true,
            allowIntroNotes = true,
            allowAlbumRequests = true,
            soloEnabled = true,
            togetherEnabled = false,
            socialEnabled = true
        )
    )
