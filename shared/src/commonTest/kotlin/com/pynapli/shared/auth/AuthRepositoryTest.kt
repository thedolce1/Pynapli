package com.pynapli.shared.auth

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.AccountDataExportDto
import com.pynapli.shared.network.dto.ActivityFeedResponseDto
import com.pynapli.shared.network.dto.ActivitySummaryDto
import com.pynapli.shared.network.dto.AgeVerificationRequirementsDto
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.AuthUserDto
import com.pynapli.shared.network.dto.BlockSummaryDto
import com.pynapli.shared.network.dto.BlockUserRequestDto
import com.pynapli.shared.network.dto.BlocksListDto
import com.pynapli.shared.network.dto.BoostActivationDto
import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import com.pynapli.shared.network.dto.CreateProfileLinkInvitationRequestDto
import com.pynapli.shared.network.dto.CreateReportRequestDto
import com.pynapli.shared.network.dto.CurrentLocationDto
import com.pynapli.shared.network.dto.DeleteAccountRequestDto
import com.pynapli.shared.network.dto.DeleteAccountResponseDto
import com.pynapli.shared.network.dto.DismissActivityEventResponseDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import com.pynapli.shared.network.dto.HiddenProfileSummaryDto
import com.pynapli.shared.network.dto.HiddenProfilesListDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.MarkActivitySeenRequestDto
import com.pynapli.shared.network.dto.MarkActivitySeenResponseDto
import com.pynapli.shared.network.dto.MatchesListResponseDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.MediaAlbumGrantDto
import com.pynapli.shared.network.dto.MediaAlbumGrantsResponseDto
import com.pynapli.shared.network.dto.MediaAlbumItemDto
import com.pynapli.shared.network.dto.MediaAlbumItemsResponseDto
import com.pynapli.shared.network.dto.MediaUploadSlotDto
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.NotificationPreferencesDto
import com.pynapli.shared.network.dto.ProfileLinksSummaryDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.PushDeviceRegistrationResponseDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import com.pynapli.shared.network.dto.RegisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.ReportDto
import com.pynapli.shared.network.dto.ReportHistoryListDto
import com.pynapli.shared.network.dto.RequestAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RequestAlbumAccessResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto
import com.pynapli.shared.network.dto.SendFlirtRequestDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import com.pynapli.shared.network.dto.SendTypingIndicatorRequestDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import com.pynapli.shared.network.dto.StorePurchaseResponseDto
import com.pynapli.shared.network.dto.SubmitDevStorePurchaseRequestDto
import com.pynapli.shared.network.dto.SubmitPlayStoreSubscriptionPurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncResponseDto
import com.pynapli.shared.network.dto.SupportContactDto
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeActionResponseDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.pynapli.shared.network.dto.SwipeDeckResponseDto
import com.pynapli.shared.network.dto.SwipeRewindResponseDto
import com.pynapli.shared.network.dto.UnregisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateNotificationPreferencesRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AuthRepositoryTest {
    @Test
    fun loginPersistsReturnedSession() =
        runTest {
            val sessionStore = InMemorySessionStore()
            val apiClient =
                FakeApiClient(
                    loginResult = AppResult.Success(testSession("access-1", "refresh-1")),
                )
            val repository = AuthRepository(apiClient, sessionStore)

            val result = repository.loginEmail("alex@example.com", "correct horse battery", "android", "0.1.0")

            val success = assertIs<AppResult.Success<StoredSession>>(result)
            assertEquals("access-1", success.value.accessToken)
            assertEquals("access-1", sessionStore.currentSession()?.accessToken)
            assertEquals("access-1", sessionStore.accessToken())
        }

    @Test
    fun signupPersistsReturnedSession() =
        runTest {
            val sessionStore = InMemorySessionStore()
            val apiClient =
                FakeApiClient(
                    signupResult = AppResult.Success(testSession("access-2", "refresh-2")),
                )
            val repository = AuthRepository(apiClient, sessionStore)

            val result =
                repository.signupEmail(
                    email = "maya@example.com",
                    password = "correct horse battery",
                    birthdate = "1990-01-01",
                    confirmedAdult = true,
                    acceptedTerms = true,
                    acceptedPrivacy = true,
                    platform = "android",
                    appVersion = "0.1.0",
                )

            val success = assertIs<AppResult.Success<StoredSession>>(result)
            assertEquals("access-2", success.value.accessToken)
            assertEquals("refresh-2", sessionStore.currentSession()?.refreshToken)
            assertEquals("1990-01-01", apiClient.lastSignupRequest?.birthdate)
            assertEquals(true, apiClient.lastSignupRequest?.confirmedAdult)
            assertEquals(true, apiClient.lastSignupRequest?.acceptedTerms)
            assertEquals(true, apiClient.lastSignupRequest?.acceptedPrivacy)
        }

    @Test
    fun forwardsDeviceIdIntoSignupAndLoginRequests() =
        runTest {
            val apiClient =
                FakeApiClient(
                    signupResult = AppResult.Success(testSession("a", "r")),
                    loginResult = AppResult.Success(testSession("a", "r")),
                )
            val repository = AuthRepository(apiClient, InMemorySessionStore())

            repository.signupEmail(
                email = "maya@example.com",
                password = "correct horse battery",
                birthdate = "1990-01-01",
                confirmedAdult = true,
                acceptedTerms = true,
                acceptedPrivacy = true,
                platform = "android",
                appVersion = "0.1.0",
                deviceId = "install-abc",
            )
            assertEquals("install-abc", apiClient.lastSignupRequest?.deviceId)

            repository.loginEmail(
                email = "maya@example.com",
                password = "correct horse battery",
                platform = "android",
                appVersion = "0.1.0",
                deviceId = "install-abc",
            )
            assertEquals("install-abc", apiClient.lastLoginRequest?.deviceId)
        }

    @Test
    fun logoutClearsSessionEvenWhenRemoteLogoutFails() =
        runTest {
            val sessionStore = InMemorySessionStore()
            sessionStore.save(testSession("access-3", "refresh-3"))
            val apiClient =
                FakeApiClient(
                    logoutResult = AppResult.Failure(AppError("NETWORK_ERROR", "Network request failed.")),
                )
            val repository = AuthRepository(apiClient, sessionStore)

            repository.logout()

            assertNull(sessionStore.currentSession())
        }

    @Test
    fun currentSessionValidatedClearsTerminalAuthFailure() =
        runTest {
            val sessionStore = InMemorySessionStore()
            sessionStore.save(testSession("stale-access", "stale-refresh"))
            val apiClient =
                FakeApiClient(
                    meResult = AppResult.Failure(AppError("UNAUTHENTICATED", "Please sign in again.")),
                )
            val repository = AuthRepository(apiClient, sessionStore)

            val restored = repository.currentSessionValidated()

            assertNull(restored)
            assertNull(sessionStore.currentSession())
        }

    @Test
    fun currentSessionValidatedKeepsASessionAnotherTabRotatedMeanwhile() =
        runTest {
            val sessionStore = InMemorySessionStore()
            sessionStore.save(testSession("stale-access", "stale-refresh"))
            val apiClient =
                FakeApiClient(
                    meResult = AppResult.Failure(AppError("UNAUTHENTICATED", "Please sign in again.")),
                    // Another tab sharing the store rotates the session while this check is in flight.
                    onGetMe = { sessionStore.save(testSession("other-tab-access", "other-tab-refresh")) },
                )
            val repository = AuthRepository(apiClient, sessionStore)

            val restored = repository.currentSessionValidated()

            // The rejection was for the token this call validated with, not the one now stored.
            assertEquals("other-tab-access", restored?.accessToken)
            assertEquals("other-tab-refresh", sessionStore.currentSession()?.refreshToken)
        }

    @Test
    fun currentSessionValidatedKeepsSessionOnRecoverableFailure() =
        runTest {
            val sessionStore = InMemorySessionStore()
            sessionStore.save(testSession("access-4", "refresh-4"))
            val apiClient =
                FakeApiClient(
                    meResult = AppResult.Failure(AppError("NETWORK_ERROR", "Network request failed.")),
                )
            val repository = AuthRepository(apiClient, sessionStore)

            val restored = repository.currentSessionValidated()

            assertEquals("access-4", restored?.accessToken)
            assertEquals("access-4", sessionStore.currentSession()?.accessToken)
        }

    private fun testSession(
        accessToken: String,
        refreshToken: String,
    ): AuthSessionDto =
        AuthSessionDto(
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = AuthUserDto(userId = "user-1", status = "active"),
        )
}

private class InMemorySessionStore : SessionStore {
    private var session: StoredSession? = null

    override suspend fun currentSession(): StoredSession? = session

    override suspend fun save(session: AuthSessionDto) {
        this.session =
            StoredSession(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                userId = session.user.userId,
                userStatus = session.user.status,
            )
    }

    override suspend fun clear() {
        session = null
    }
}

private class FakeApiClient(
    private val signupResult: AppResult<AuthSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented.")),
    private val loginResult: AppResult<AuthSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented.")),
    private val logoutResult: AppResult<LogoutResponseDto> = AppResult.Success(LogoutResponseDto(loggedOut = true)),
    private val meResult: AppResult<MeDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented.")),
    private val onGetMe: suspend () -> Unit = {},
) : PynapliApiClient {
    var lastSignupRequest: EmailSignupRequestDto? = null
        private set

    var lastLoginRequest: EmailLoginRequestDto? = null
        private set

    override suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<AuthSessionDto> {
        lastSignupRequest = request
        return signupResult
    }

    override suspend fun emailLogin(request: EmailLoginRequestDto): AppResult<AuthSessionDto> {
        lastLoginRequest = request
        return loginResult
    }

    override suspend fun refreshSession(request: RefreshSessionRequestDto): AppResult<AuthSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun logout(): AppResult<LogoutResponseDto> = logoutResult

    override suspend fun getLatestDataExportRequest(): AppResult<AccountDataExportDto?> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun requestDataExport(): AppResult<AccountDataExportDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun deleteAccount(request: DeleteAccountRequestDto): AppResult<DeleteAccountResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun updateCurrentLocation(request: UpdateCurrentLocationRequestDto): AppResult<CurrentLocationDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun queryGrid(request: GridQueryRequestDto): AppResult<GridQueryResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listSavedGridFilters(): AppResult<com.pynapli.shared.network.dto.SavedGridFiltersListDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun createSavedGridFilter(
        request: com.pynapli.shared.network.dto.CreateSavedGridFilterRequestDto,
    ): AppResult<com.pynapli.shared.network.dto.SavedGridFilterDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun deleteSavedGridFilter(id: String): AppResult<Unit> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listSavedSwipeFilters(): AppResult<com.pynapli.shared.network.dto.SavedSwipeFiltersListDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun createSavedSwipeFilter(
        request: com.pynapli.shared.network.dto.CreateSavedSwipeFilterRequestDto,
    ): AppResult<com.pynapli.shared.network.dto.SavedSwipeFilterDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun deleteSavedSwipeFilter(id: String): AppResult<Unit> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getTopPicks(): AppResult<com.pynapli.shared.network.dto.TopPicksResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getTravelLocation(): AppResult<com.pynapli.shared.network.dto.TravelLocationDto?> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun setTravelLocation(
        request: com.pynapli.shared.network.dto.SetTravelLocationRequestDto,
    ): AppResult<com.pynapli.shared.network.dto.TravelLocationDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun clearTravelLocation(): AppResult<Unit> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun searchTravelPlaces(
        query: String,
    ): AppResult<com.pynapli.shared.network.dto.TravelPlaceSearchResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getTonightAvailability(): AppResult<com.pynapli.shared.network.dto.TonightAvailabilityDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun setTonightAvailability(
        request: com.pynapli.shared.network.dto.SetTonightAvailableRequestDto,
    ): AppResult<com.pynapli.shared.network.dto.TonightAvailabilityDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun clearTonightAvailability(): AppResult<com.pynapli.shared.network.dto.TonightAvailabilityDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getTonightFeed(
        radiusMeters: Int,
        intent: String?,
    ): AppResult<com.pynapli.shared.network.dto.TonightFeedResponseDto> =
        AppResult.Success(
            com.pynapli.shared.network.dto
                .TonightFeedResponseDto(),
        )

    override suspend fun getCurrentTonightSession(): AppResult<com.pynapli.shared.network.dto.CurrentTonightSessionDto> =
        AppResult.Success(
            com.pynapli.shared.network.dto
                .CurrentTonightSessionDto(),
        )

    override suspend fun proposeTonightSession(
        request: com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto,
    ): AppResult<com.pynapli.shared.network.dto.TonightSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun approveTonightSession(sessionId: String): AppResult<com.pynapli.shared.network.dto.TonightSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun declineTonightSession(sessionId: String): AppResult<com.pynapli.shared.network.dto.TonightSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun endTonightSession(sessionId: String): AppResult<com.pynapli.shared.network.dto.TonightSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun createPsyncSession(
        request: com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto,
    ): AppResult<com.pynapli.shared.network.dto.PsyncSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getCurrentPsyncSession(): AppResult<com.pynapli.shared.network.dto.CurrentPsyncSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getPsyncSession(sessionId: String): AppResult<com.pynapli.shared.network.dto.PsyncSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun acceptPsyncSession(sessionId: String): AppResult<com.pynapli.shared.network.dto.PsyncSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun denyPsyncSession(sessionId: String): AppResult<com.pynapli.shared.network.dto.PsyncSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun suggestPsyncFilters(
        sessionId: String,
        request: com.pynapli.shared.network.dto.SuggestPsyncFiltersRequestDto,
    ): AppResult<com.pynapli.shared.network.dto.PsyncSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun leavePsyncSession(sessionId: String): AppResult<com.pynapli.shared.network.dto.PsyncSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun startPsyncSession(sessionId: String): AppResult<com.pynapli.shared.network.dto.PsyncSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getPsyncDeck(sessionId: String): AppResult<com.pynapli.shared.network.dto.PsyncDeckResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun submitPsyncAction(
        sessionId: String,
        request: com.pynapli.shared.network.dto.SubmitPsyncActionRequestDto,
    ): AppResult<com.pynapli.shared.network.dto.PsyncActionResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun pausePsyncSession(sessionId: String): AppResult<com.pynapli.shared.network.dto.PsyncSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun resumePsyncSession(sessionId: String): AppResult<com.pynapli.shared.network.dto.PsyncSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun completePsyncSession(sessionId: String): AppResult<com.pynapli.shared.network.dto.PsyncRecapDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getPsyncRecap(sessionId: String): AppResult<com.pynapli.shared.network.dto.PsyncRecapDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun nudgePsyncSession(sessionId: String): AppResult<com.pynapli.shared.network.dto.PsyncNudgeResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getMe(): AppResult<MeDto> {
        onGetMe()
        return meResult
    }

    override suspend fun getAgeVerificationRequirements(): AppResult<AgeVerificationRequirementsDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getAgeVerificationStatus(): AppResult<AgeVerificationStatusDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun startAgeVerification(
        request: StartAgeVerificationRequestDto,
    ): AppResult<AgeVerificationStatusDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun updateProfile(request: UpdateProfileRequestDto): AppResult<MeDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun updateDiscoverySettings(request: UpdateDiscoverySettingsRequestDto): AppResult<MeDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getNotificationPreferences(): AppResult<NotificationPreferencesDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun updateNotificationPreferences(
        request: UpdateNotificationPreferencesRequestDto,
    ): AppResult<NotificationPreferencesDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun registerPushDevice(
        request: RegisterPushDeviceRequestDto,
    ): AppResult<PushDeviceRegistrationResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun unregisterPushDevice(request: UnregisterPushDeviceRequestDto): AppResult<Unit> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getPublicProfile(userId: String): AppResult<PublicProfileDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listProfileLinks(): AppResult<ProfileLinksSummaryDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun createProfileLinkInvitation(
        request: CreateProfileLinkInvitationRequestDto,
    ): AppResult<ProfileLinksSummaryDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun acceptProfileLinkInvitation(linkId: String): AppResult<ProfileLinksSummaryDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun declineProfileLinkInvitation(linkId: String): AppResult<ProfileLinksSummaryDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun unlinkCurrentProfileLink(): AppResult<ProfileLinksSummaryDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getSubscriptionEntitlement(): AppResult<SubscriptionEntitlementDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getSubscriptionInventory(): AppResult<SubscriptionInventoryResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun submitPlayStoreSubscriptionPurchase(
        request: SubmitPlayStoreSubscriptionPurchaseRequestDto,
    ): AppResult<SubscriptionPurchaseSyncResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun submitDevStorePurchase(
        request: SubmitDevStorePurchaseRequestDto,
    ): AppResult<StorePurchaseResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun activateBoost(): AppResult<BoostActivationDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listActivity(
        limit: Int?,
        tab: String?,
        cursor: String?,
    ): AppResult<ActivityFeedResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getActivitySummary(): AppResult<ActivitySummaryDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun markActivitySeen(
        request: MarkActivitySeenRequestDto,
    ): AppResult<MarkActivitySeenResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun dismissActivityEvent(eventId: String): AppResult<DismissActivityEventResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun sendFlirt(request: SendFlirtRequestDto): AppResult<SendFlirtResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun requestAlbumAccess(
        request: RequestAlbumAccessRequestDto,
    ): AppResult<RequestAlbumAccessResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun respondToAlbumAccessRequest(
        eventId: String,
        request: RespondAlbumAccessRequestDto,
    ): AppResult<RespondAlbumAccessResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun createDirectConversation(
        request: CreateDirectConversationRequestDto,
    ): AppResult<ConversationDetailDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listConversations(): AppResult<ConversationListResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getConversationDetail(
        conversationId: String,
        before: String?,
        limit: Int?,
    ): AppResult<ConversationDetailDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun sendMessage(
        conversationId: String,
        request: SendMessageRequestDto,
    ): AppResult<MessageDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun sendTypingIndicator(
        conversationId: String,
        request: SendTypingIndicatorRequestDto,
    ): AppResult<Unit> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun setConversationExpiry(
        conversationId: String,
        request: com.pynapli.shared.network.dto.SetConversationExpiryRequestDto,
    ): AppResult<ConversationDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun createMessageMediaUploadSlot(
        request: com.pynapli.shared.network.dto.CreateMessageMediaUploadRequestDto,
    ): AppResult<com.pynapli.shared.network.dto.MessageMediaUploadSlotDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun sendMediaMessage(
        conversationId: String,
        request: com.pynapli.shared.network.dto.SendMediaMessageRequestDto,
    ): AppResult<MessageDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun revealMessageMedia(messageId: String): AppResult<MessageDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun deleteMessage(messageId: String): AppResult<MessageDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun inviteLinkedPartnerToConversation(conversationId: String): AppResult<ConversationDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun acceptConversationPartnerInvite(conversationId: String): AppResult<ConversationDetailDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun declineConversationPartnerInvite(
        conversationId: String,
    ): AppResult<ConversationPartnerInviteResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun leaveConversation(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun createMediaUpload(request: CreateMediaUploadRequestDto): AppResult<MediaUploadSlotDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun completeMediaUpload(
        mediaAssetId: String,
        request: CompleteMediaUploadRequestDto,
    ): AppResult<MediaAlbumItemDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listMediaAlbum(albumKind: String): AppResult<MediaAlbumItemsResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun deleteMediaAsset(mediaAssetId: String): AppResult<Unit> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun reorderMediaAlbum(
        albumKind: String,
        request: com.pynapli.shared.network.dto.ReorderMediaAlbumRequestDto,
    ): AppResult<MediaAlbumItemsResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listUserPrivateAlbum(ownerUserId: String): AppResult<MediaAlbumItemsResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listOwnPrivateAlbumGrants(): AppResult<MediaAlbumGrantsResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun revokePrivateAlbumAccess(granteeUserId: String): AppResult<MediaAlbumGrantDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun blockUser(
        targetUserId: String,
        request: BlockUserRequestDto,
    ): AppResult<BlockSummaryDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun unblockUser(targetUserId: String): AppResult<Unit> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listBlocks(): AppResult<BlocksListDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun hideProfile(targetUserId: String): AppResult<HiddenProfileSummaryDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun unhideProfile(targetUserId: String): AppResult<Unit> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listHiddenProfiles(): AppResult<HiddenProfilesListDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun submitReport(request: CreateReportRequestDto): AppResult<ReportDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listReports(limit: Int?): AppResult<ReportHistoryListDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getSupportContact(): AppResult<SupportContactDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun querySwipeDeck(request: SwipeDeckRequestDto): AppResult<SwipeDeckResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun submitSwipeAction(request: SwipeActionRequestDto): AppResult<SwipeActionResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun rewindSwipeAction(): AppResult<SwipeRewindResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun likeBack(targetUserId: String): AppResult<com.pynapli.shared.network.dto.SwipeActionResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listMatches(): AppResult<MatchesListResponseDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override fun mediaAssetContentUrl(mediaAssetId: String): String = "https://media.example/$mediaAssetId"
}
