package com.pynapli.shared.auth

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.ActivityFeedResponseDto
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.AuthUserDto
import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.MediaAlbumItemsResponseDto
import com.pynapli.shared.network.dto.MediaAlbumItemDto
import com.pynapli.shared.network.dto.MediaUploadSlotDto
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.PresenceHeartbeatResponseDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import com.pynapli.shared.network.dto.RequestAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RequestAlbumAccessResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import com.pynapli.shared.network.dto.SendFlirtRequestDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto
import com.pynapli.shared.network.dto.CurrentLocationDto
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AuthRepositoryTest {
    @Test
    fun loginPersistsReturnedSession() = runBlocking {
        val sessionStore = InMemorySessionStore()
        val apiClient = FakeApiClient(
            loginResult = AppResult.Success(testSession("access-1", "refresh-1"))
        )
        val repository = AuthRepository(apiClient, sessionStore)

        val result = repository.loginEmail("alex@example.com", "correct horse battery", "android", "0.1.0")

        val success = assertIs<AppResult.Success<StoredSession>>(result)
        assertEquals("access-1", success.value.accessToken)
        assertEquals("access-1", sessionStore.currentSession()?.accessToken)
        assertEquals("access-1", sessionStore.accessToken())
    }

    @Test
    fun signupPersistsReturnedSession() = runBlocking {
        val sessionStore = InMemorySessionStore()
        val apiClient = FakeApiClient(
            signupResult = AppResult.Success(testSession("access-2", "refresh-2"))
        )
        val repository = AuthRepository(apiClient, sessionStore)

        val result = repository.signupEmail("maya@example.com", "correct horse battery", "android", "0.1.0")

        val success = assertIs<AppResult.Success<StoredSession>>(result)
        assertEquals("access-2", success.value.accessToken)
        assertEquals("refresh-2", sessionStore.currentSession()?.refreshToken)
    }

    @Test
    fun logoutClearsSessionEvenWhenRemoteLogoutFails() = runBlocking {
        val sessionStore = InMemorySessionStore()
        sessionStore.save(testSession("access-3", "refresh-3"))
        val apiClient = FakeApiClient(
            logoutResult = AppResult.Failure(AppError("NETWORK_ERROR", "Network request failed."))
        )
        val repository = AuthRepository(apiClient, sessionStore)

        repository.logout()

        assertNull(sessionStore.currentSession())
    }

    private fun testSession(accessToken: String, refreshToken: String): AuthSessionDto = AuthSessionDto(
        accessToken = accessToken,
        refreshToken = refreshToken,
        user = AuthUserDto(userId = "user-1", status = "active")
    )
}

private class InMemorySessionStore : SessionStore {
    private var session: StoredSession? = null

    override suspend fun currentSession(): StoredSession? = session

    override suspend fun save(session: AuthSessionDto) {
        this.session = StoredSession(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            userId = session.user.userId,
            userStatus = session.user.status
        )
    }

    override suspend fun clear() {
        session = null
    }
}

private class FakeApiClient(
    private val signupResult: AppResult<AuthSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented.")),
    private val loginResult: AppResult<AuthSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented.")),
    private val logoutResult: AppResult<LogoutResponseDto> = AppResult.Success(LogoutResponseDto(loggedOut = true))
) : PynapliApiClient {
    override suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<AuthSessionDto> = signupResult

    override suspend fun emailLogin(request: EmailLoginRequestDto): AppResult<AuthSessionDto> = loginResult

    override suspend fun refreshSession(request: RefreshSessionRequestDto): AppResult<AuthSessionDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun logout(): AppResult<LogoutResponseDto> = logoutResult

    override suspend fun updateCurrentLocation(request: UpdateCurrentLocationRequestDto): AppResult<CurrentLocationDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun queryGrid(request: GridQueryRequestDto): AppResult<GridQueryResponseDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getPublicProfile(userId: String): AppResult<PublicProfileDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listActivity(limit: Int?): AppResult<ActivityFeedResponseDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun sendFlirt(request: SendFlirtRequestDto): AppResult<SendFlirtResponseDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun requestAlbumAccess(
        request: RequestAlbumAccessRequestDto
    ): AppResult<RequestAlbumAccessResponseDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun respondToAlbumAccessRequest(
        eventId: String,
        request: RespondAlbumAccessRequestDto
    ): AppResult<RespondAlbumAccessResponseDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun createDirectConversation(
        request: CreateDirectConversationRequestDto
    ): AppResult<ConversationDetailDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listConversations(): AppResult<ConversationListResponseDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun getConversationDetail(
        conversationId: String,
        before: String?,
        limit: Int?
    ): AppResult<ConversationDetailDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun sendMessage(conversationId: String, request: SendMessageRequestDto): AppResult<MessageDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun createMediaUpload(request: CreateMediaUploadRequestDto): AppResult<MediaUploadSlotDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun completeMediaUpload(
        mediaAssetId: String,
        request: CompleteMediaUploadRequestDto
    ): AppResult<MediaAlbumItemDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listMediaAlbum(albumKind: String): AppResult<MediaAlbumItemsResponseDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listUserPrivateAlbum(ownerUserId: String): AppResult<MediaAlbumItemsResponseDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override fun mediaAssetContentUrl(mediaAssetId: String): String =
        "https://media.example/$mediaAssetId"

    override suspend fun heartbeatPresence(): AppResult<PresenceHeartbeatResponseDto> =
        AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))
}
