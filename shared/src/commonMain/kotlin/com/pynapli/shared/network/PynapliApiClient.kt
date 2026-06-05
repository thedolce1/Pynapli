package com.pynapli.shared.network

import com.pynapli.shared.core.config.PynapliConfig
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AccountDataExportDto
import com.pynapli.shared.network.dto.ActivityFeedResponseDto
import com.pynapli.shared.network.dto.ActivitySummaryDto
import com.pynapli.shared.network.dto.AgeVerificationRequirementsDto
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.ApiErrorDto
import com.pynapli.shared.network.dto.AuthSessionDto
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
import com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto
import com.pynapli.shared.network.dto.CreateReportRequestDto
import com.pynapli.shared.network.dto.CreateSavedGridFilterRequestDto
import com.pynapli.shared.network.dto.CreateSavedSwipeFilterRequestDto
import com.pynapli.shared.network.dto.CurrentLocationDto
import com.pynapli.shared.network.dto.CurrentPsyncSessionDto
import com.pynapli.shared.network.dto.CurrentTonightSessionDto
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
import com.pynapli.shared.network.dto.PresenceHeartbeatResponseDto
import com.pynapli.shared.network.dto.ProfileLinksSummaryDto
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto
import com.pynapli.shared.network.dto.PsyncActionResponseDto
import com.pynapli.shared.network.dto.PsyncDeckResponseDto
import com.pynapli.shared.network.dto.PsyncNudgeResponseDto
import com.pynapli.shared.network.dto.PsyncRecapDto
import com.pynapli.shared.network.dto.PsyncSessionDto
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
import com.pynapli.shared.network.dto.SavedGridFilterDto
import com.pynapli.shared.network.dto.SavedGridFiltersListDto
import com.pynapli.shared.network.dto.SavedSwipeFilterDto
import com.pynapli.shared.network.dto.SavedSwipeFiltersListDto
import com.pynapli.shared.network.dto.SendFlirtRequestDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import com.pynapli.shared.network.dto.SendTypingIndicatorRequestDto
import com.pynapli.shared.network.dto.SetTonightAvailableRequestDto
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import com.pynapli.shared.network.dto.StorePurchaseResponseDto
import com.pynapli.shared.network.dto.SubmitDevStorePurchaseRequestDto
import com.pynapli.shared.network.dto.SubmitPlayStoreSubscriptionPurchaseRequestDto
import com.pynapli.shared.network.dto.SubmitPsyncActionRequestDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncResponseDto
import com.pynapli.shared.network.dto.SuggestPsyncFiltersRequestDto
import com.pynapli.shared.network.dto.SupportContactDto
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeActionResponseDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.pynapli.shared.network.dto.SwipeDeckResponseDto
import com.pynapli.shared.network.dto.SwipeRewindResponseDto
import com.pynapli.shared.network.dto.TonightAvailabilityDto
import com.pynapli.shared.network.dto.TonightFeedResponseDto
import com.pynapli.shared.network.dto.TonightSessionDto
import com.pynapli.shared.network.dto.TopPicksResponseDto
import com.pynapli.shared.network.dto.TravelLocationDto
import com.pynapli.shared.network.dto.UnregisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateNotificationPreferencesRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

interface PynapliApiClient :
    AuthApi,
    ConversationApi,
    GridApi,
    SwipeApi,
    ProfileApi,
    ActivityApi {
    suspend fun getLatestDataExportRequest(): AppResult<AccountDataExportDto?>

    suspend fun requestDataExport(): AppResult<AccountDataExportDto>

    suspend fun deleteAccount(request: DeleteAccountRequestDto): AppResult<DeleteAccountResponseDto>

    suspend fun updateCurrentLocation(request: UpdateCurrentLocationRequestDto): AppResult<CurrentLocationDto>

    suspend fun getTonightAvailability(): AppResult<TonightAvailabilityDto>

    suspend fun setTonightAvailability(request: SetTonightAvailableRequestDto = SetTonightAvailableRequestDto()): AppResult<TonightAvailabilityDto>

    suspend fun clearTonightAvailability(): AppResult<TonightAvailabilityDto>

    suspend fun getTonightFeed(
        radiusMeters: Int = 50_000,
        intent: String? = null,
    ): AppResult<TonightFeedResponseDto>

    suspend fun getCurrentTonightSession(): AppResult<CurrentTonightSessionDto>

    suspend fun proposeTonightSession(request: ProposeTonightSessionRequestDto): AppResult<TonightSessionDto>

    suspend fun approveTonightSession(sessionId: String): AppResult<TonightSessionDto>

    suspend fun declineTonightSession(sessionId: String): AppResult<TonightSessionDto>

    suspend fun endTonightSession(sessionId: String): AppResult<TonightSessionDto>

    // Psync (synchronized partner swipe). Server is the product authority; the client just
    // negotiates, swipes the shared deck privately, and reads the recap.
    suspend fun createPsyncSession(
        request: CreatePsyncSessionRequestDto = CreatePsyncSessionRequestDto(),
    ): AppResult<PsyncSessionDto>

    suspend fun getCurrentPsyncSession(): AppResult<CurrentPsyncSessionDto>

    suspend fun getPsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun acceptPsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun denyPsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun suggestPsyncFilters(
        sessionId: String,
        request: SuggestPsyncFiltersRequestDto,
    ): AppResult<PsyncSessionDto>

    suspend fun leavePsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun startPsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun getPsyncDeck(sessionId: String): AppResult<PsyncDeckResponseDto>

    suspend fun submitPsyncAction(
        sessionId: String,
        request: SubmitPsyncActionRequestDto,
    ): AppResult<PsyncActionResponseDto>

    suspend fun pausePsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun resumePsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun completePsyncSession(sessionId: String): AppResult<PsyncRecapDto>

    suspend fun getPsyncRecap(sessionId: String): AppResult<PsyncRecapDto>

    suspend fun nudgePsyncSession(sessionId: String): AppResult<PsyncNudgeResponseDto>

    suspend fun getAgeVerificationRequirements(): AppResult<AgeVerificationRequirementsDto>

    suspend fun getAgeVerificationStatus(): AppResult<AgeVerificationStatusDto>

    suspend fun startAgeVerification(
        request: StartAgeVerificationRequestDto,
    ): AppResult<AgeVerificationStatusDto>

    suspend fun getNotificationPreferences(): AppResult<NotificationPreferencesDto>

    suspend fun updateNotificationPreferences(
        request: UpdateNotificationPreferencesRequestDto,
    ): AppResult<NotificationPreferencesDto>

    suspend fun registerPushDevice(
        request: RegisterPushDeviceRequestDto,
    ): AppResult<PushDeviceRegistrationResponseDto>

    suspend fun unregisterPushDevice(request: UnregisterPushDeviceRequestDto): AppResult<Unit>

    suspend fun listProfileLinks(): AppResult<ProfileLinksSummaryDto>

    suspend fun createProfileLinkInvitation(
        request: CreateProfileLinkInvitationRequestDto,
    ): AppResult<ProfileLinksSummaryDto>

    suspend fun acceptProfileLinkInvitation(linkId: String): AppResult<ProfileLinksSummaryDto>

    suspend fun declineProfileLinkInvitation(linkId: String): AppResult<ProfileLinksSummaryDto>

    suspend fun unlinkCurrentProfileLink(): AppResult<ProfileLinksSummaryDto>

    suspend fun getSubscriptionEntitlement(): AppResult<SubscriptionEntitlementDto>

    suspend fun getSubscriptionInventory(): AppResult<SubscriptionInventoryResponseDto>

    suspend fun submitPlayStoreSubscriptionPurchase(
        request: SubmitPlayStoreSubscriptionPurchaseRequestDto,
    ): AppResult<SubscriptionPurchaseSyncResponseDto>

    suspend fun submitDevStorePurchase(request: SubmitDevStorePurchaseRequestDto): AppResult<StorePurchaseResponseDto>

    suspend fun activateBoost(): AppResult<BoostActivationDto>

    suspend fun createMediaUpload(request: CreateMediaUploadRequestDto): AppResult<MediaUploadSlotDto>

    suspend fun completeMediaUpload(
        mediaAssetId: String,
        request: CompleteMediaUploadRequestDto,
    ): AppResult<MediaAlbumItemDto>

    suspend fun listMediaAlbum(albumKind: String): AppResult<MediaAlbumItemsResponseDto>

    suspend fun listUserPrivateAlbum(ownerUserId: String): AppResult<MediaAlbumItemsResponseDto>

    suspend fun listOwnPrivateAlbumGrants(): AppResult<MediaAlbumGrantsResponseDto>

    suspend fun revokePrivateAlbumAccess(granteeUserId: String): AppResult<MediaAlbumGrantDto>

    suspend fun blockUser(
        targetUserId: String,
        request: BlockUserRequestDto = BlockUserRequestDto(),
    ): AppResult<BlockSummaryDto>

    suspend fun unblockUser(targetUserId: String): AppResult<Unit>

    suspend fun listBlocks(): AppResult<BlocksListDto>

    suspend fun hideProfile(targetUserId: String): AppResult<HiddenProfileSummaryDto>

    suspend fun unhideProfile(targetUserId: String): AppResult<Unit>

    suspend fun listHiddenProfiles(): AppResult<HiddenProfilesListDto>

    suspend fun submitReport(request: CreateReportRequestDto): AppResult<ReportDto>

    suspend fun listReports(limit: Int? = null): AppResult<ReportHistoryListDto>

    suspend fun getSupportContact(): AppResult<SupportContactDto>

    fun mediaAssetContentUrl(mediaAssetId: String): String

    suspend fun heartbeatPresence(): AppResult<PresenceHeartbeatResponseDto>
}

class KtorPynapliApiClient(
    private val config: PynapliConfig,
    private val accessTokenProvider: AccessTokenProvider,
    private val httpClient: HttpClient = createPynapliHttpClient(),
    private val json: Json = PynapliJson,
) : PynapliApiClient,
    AuthApi by KtorAuthApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    ConversationApi by KtorConversationApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    GridApi by KtorGridApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    SwipeApi by KtorSwipeApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    ProfileApi by KtorProfileApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    ActivityApi by KtorActivityApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)) {
    override suspend fun getLatestDataExportRequest(): AppResult<AccountDataExportDto?> =
        authenticatedPost { token ->
            val response =
                httpClient.get(config.endpoint("/v1/account/data-export")) {
                    bearerAuth(token)
                }
            if (response.status.value == 204) {
                AppResult.Success(null)
            } else {
                @Suppress("UNCHECKED_CAST")
                response.toAppResult<AccountDataExportDto>(json) as AppResult<AccountDataExportDto?>
            }
        }

    override suspend fun requestDataExport(): AppResult<AccountDataExportDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/account/data-export")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun deleteAccount(request: DeleteAccountRequestDto): AppResult<DeleteAccountResponseDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/account/delete")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun updateCurrentLocation(request: UpdateCurrentLocationRequestDto): AppResult<CurrentLocationDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/location/current")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun getTonightAvailability(): AppResult<TonightAvailabilityDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/me/availability/tonight")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun setTonightAvailability(request: SetTonightAvailableRequestDto): AppResult<TonightAvailabilityDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/me/availability/tonight")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun clearTonightAvailability(): AppResult<TonightAvailabilityDto> =
        authenticatedPost { token ->
            httpClient
                .delete(config.endpoint("/v1/me/availability/tonight")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun getTonightFeed(
        radiusMeters: Int,
        intent: String?,
    ): AppResult<TonightFeedResponseDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/tonight/feed")) {
                    bearerAuth(token)
                    parameter("radiusMeters", radiusMeters)
                    intent?.let { parameter("intent", it) }
                }.toAppResult(json)
        }

    override suspend fun getCurrentTonightSession(): AppResult<CurrentTonightSessionDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/me/tonight/session")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun proposeTonightSession(request: ProposeTonightSessionRequestDto): AppResult<TonightSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/tonight/sessions")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun approveTonightSession(sessionId: String): AppResult<TonightSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/tonight/sessions/$sessionId/approve")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun declineTonightSession(sessionId: String): AppResult<TonightSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/tonight/sessions/$sessionId/decline")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun endTonightSession(sessionId: String): AppResult<TonightSessionDto> =
        authenticatedPost { token ->
            httpClient
                .delete(config.endpoint("/v1/tonight/sessions/$sessionId")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun createPsyncSession(request: CreatePsyncSessionRequestDto): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun getCurrentPsyncSession(): AppResult<CurrentPsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/me/psync/session")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun getPsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/psync/sessions/$sessionId")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun acceptPsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/accept")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun denyPsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/deny")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun suggestPsyncFilters(
        sessionId: String,
        request: SuggestPsyncFiltersRequestDto,
    ): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/suggest-filters")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun leavePsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/leave")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun startPsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/start")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun getPsyncDeck(sessionId: String): AppResult<PsyncDeckResponseDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/psync/sessions/$sessionId/deck")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun submitPsyncAction(
        sessionId: String,
        request: SubmitPsyncActionRequestDto,
    ): AppResult<PsyncActionResponseDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/actions")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun pausePsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/pause")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun resumePsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/resume")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun completePsyncSession(sessionId: String): AppResult<PsyncRecapDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/complete")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun getPsyncRecap(sessionId: String): AppResult<PsyncRecapDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/psync/sessions/$sessionId/recap")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun nudgePsyncSession(sessionId: String): AppResult<PsyncNudgeResponseDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/nudge")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun getAgeVerificationRequirements(): AppResult<AgeVerificationRequirementsDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/verification/requirements")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun getAgeVerificationStatus(): AppResult<AgeVerificationStatusDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/verification/status")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun startAgeVerification(
        request: StartAgeVerificationRequestDto,
    ): AppResult<AgeVerificationStatusDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/verification/start")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun getNotificationPreferences(): AppResult<NotificationPreferencesDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/notifications/preferences")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun updateNotificationPreferences(
        request: UpdateNotificationPreferencesRequestDto,
    ): AppResult<NotificationPreferencesDto> =
        authenticatedPost { token ->
            httpClient
                .patch(config.endpoint("/v1/notifications/preferences")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun registerPushDevice(
        request: RegisterPushDeviceRequestDto,
    ): AppResult<PushDeviceRegistrationResponseDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/notifications/devices")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun unregisterPushDevice(request: UnregisterPushDeviceRequestDto): AppResult<Unit> =
        authenticatedPost { token ->
            val response =
                httpClient.post(config.endpoint("/v1/notifications/devices/unregister")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            if (response.status.value in 200..299) {
                AppResult.Success(Unit)
            } else {
                response.toAppResult<Unit>(json)
            }
        }

    override suspend fun listProfileLinks(): AppResult<ProfileLinksSummaryDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/profile-links")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun createProfileLinkInvitation(
        request: CreateProfileLinkInvitationRequestDto,
    ): AppResult<ProfileLinksSummaryDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/profile-links/invitations")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun acceptProfileLinkInvitation(linkId: String): AppResult<ProfileLinksSummaryDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/profile-links/invitations/$linkId/accept")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun declineProfileLinkInvitation(linkId: String): AppResult<ProfileLinksSummaryDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/profile-links/invitations/$linkId/decline")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun unlinkCurrentProfileLink(): AppResult<ProfileLinksSummaryDto> =
        authenticatedPost { token ->
            httpClient
                .delete(config.endpoint("/v1/profile-links/current")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun getSubscriptionEntitlement(): AppResult<SubscriptionEntitlementDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/subscriptions/entitlement")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun getSubscriptionInventory(): AppResult<SubscriptionInventoryResponseDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/subscriptions/inventory")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun submitPlayStoreSubscriptionPurchase(
        request: SubmitPlayStoreSubscriptionPurchaseRequestDto,
    ): AppResult<SubscriptionPurchaseSyncResponseDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/subscriptions/play-store/purchases")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun submitDevStorePurchase(request: SubmitDevStorePurchaseRequestDto): AppResult<StorePurchaseResponseDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/subscriptions/dev/purchases")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun activateBoost(): AppResult<BoostActivationDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/boosts")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun createMediaUpload(request: CreateMediaUploadRequestDto): AppResult<MediaUploadSlotDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/media/uploads")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun completeMediaUpload(
        mediaAssetId: String,
        request: CompleteMediaUploadRequestDto,
    ): AppResult<MediaAlbumItemDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/media/uploads/$mediaAssetId/complete")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun listMediaAlbum(albumKind: String): AppResult<MediaAlbumItemsResponseDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/media/albums/$albumKind")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun listUserPrivateAlbum(ownerUserId: String): AppResult<MediaAlbumItemsResponseDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/media/users/$ownerUserId/albums/private")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun listOwnPrivateAlbumGrants(): AppResult<MediaAlbumGrantsResponseDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/media/albums/private/grants")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun revokePrivateAlbumAccess(granteeUserId: String): AppResult<MediaAlbumGrantDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/media/albums/private/grants/$granteeUserId/revoke")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun blockUser(
        targetUserId: String,
        request: BlockUserRequestDto,
    ): AppResult<BlockSummaryDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/blocks/$targetUserId")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun unblockUser(targetUserId: String): AppResult<Unit> =
        authenticatedPost { token ->
            val response =
                httpClient.delete(config.endpoint("/v1/blocks/$targetUserId")) {
                    bearerAuth(token)
                }
            if (response.status.value in 200..299) {
                AppResult.Success(Unit)
            } else {
                response.toAppResult<Unit>(json)
            }
        }

    override suspend fun listBlocks(): AppResult<BlocksListDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/blocks")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun hideProfile(targetUserId: String): AppResult<HiddenProfileSummaryDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/hidden-profiles/$targetUserId")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun unhideProfile(targetUserId: String): AppResult<Unit> =
        authenticatedPost { token ->
            val response =
                httpClient.delete(config.endpoint("/v1/hidden-profiles/$targetUserId")) {
                    bearerAuth(token)
                }
            if (response.status.value in 200..299) {
                AppResult.Success(Unit)
            } else {
                response.toAppResult<Unit>(json)
            }
        }

    override suspend fun listHiddenProfiles(): AppResult<HiddenProfilesListDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/hidden-profiles")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun submitReport(request: CreateReportRequestDto): AppResult<ReportDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/reports")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun listReports(limit: Int?): AppResult<ReportHistoryListDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/reports")) {
                    bearerAuth(token)
                    if (limit != null) parameter("limit", limit)
                }.toAppResult(json)
        }

    override suspend fun getSupportContact(): AppResult<SupportContactDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/support/contact")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override fun mediaAssetContentUrl(mediaAssetId: String): String = config.endpoint("/v1/media/uploads/local/$mediaAssetId")

    override suspend fun heartbeatPresence(): AppResult<PresenceHeartbeatResponseDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/presence/heartbeat")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    private suspend inline fun <reified Request : Any, reified Response : Any> postJson(
        path: String,
        request: Request,
    ): AppResult<Response> =
        runCatching {
            httpClient
                .post(config.endpoint(path)) {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult<Response>(json)
        }.getOrElse { error ->
            AppResult.Failure(
                AppError(
                    code = "NETWORK_ERROR",
                    message = error.message ?: "Network request failed.",
                ),
            )
        }

    private suspend fun <T> authenticatedPost(
        call: suspend (String) -> AppResult<T>,
    ): AppResult<T> {
        val token = accessTokenProvider.accessToken()?.trim()
        if (token.isNullOrEmpty()) {
            return AppResult.Failure(
                AppError(
                    code = "UNAUTHENTICATED",
                    message = "Please sign in again.",
                ),
            )
        }

        return runCatching { call(token) }.getOrElse { error ->
            AppResult.Failure(
                AppError(
                    code = "NETWORK_ERROR",
                    message = error.message ?: "Network request failed.",
                ),
            )
        }
    }

    private fun PynapliConfig.endpoint(path: String): String = apiBaseUrl.trimEnd('/') + path
}

fun createPynapliHttpClient(json: Json = PynapliJson): HttpClient =
    HttpClient {
        expectSuccess = false
        install(ContentNegotiation) {
            json(json)
        }
    }

internal suspend inline fun <reified T> HttpResponse.toAppResult(json: Json): AppResult<T> {
    if (status.value in 200..299) {
        return AppResult.Success(body())
    }

    val apiError =
        runCatching {
            json.decodeFromString<ApiErrorDto>(bodyAsText())
        }.getOrNull()

    return AppResult.Failure(
        AppError(
            code = apiError?.code ?: "HTTP_${status.value}",
            message = apiError?.message ?: "Request failed.",
            recoverable = status.value >= 500 || status == HttpStatusCode.TooManyRequests,
        ),
    )
}
