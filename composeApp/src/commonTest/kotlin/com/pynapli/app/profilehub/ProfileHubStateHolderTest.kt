package com.pynapli.app.profilehub

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.AuthUserDto
import com.pynapli.shared.network.dto.DiscoverySettingsDto
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
}

private class FakeProfileHubRepository(
    private val loadMeResult: AppResult<MeDto> = AppResult.Success(testMe()),
    private val loadAgeResult: AppResult<AgeVerificationStatusDto> = AppResult.Success(
        AgeVerificationStatusDto(status = "not_started", message = "Age verification has not started.")
    ),
    private val startAgeResult: AppResult<AgeVerificationStatusDto> = AppResult.Failure(
        AppError("UNIMPLEMENTED", "Not implemented.")
    )
) : ProfileHubRepository {
    var lastStartRequest: StartAgeVerificationRequestDto? = null
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
