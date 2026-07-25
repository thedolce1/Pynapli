package com.pynapli.app.auth

import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.auth.SessionStore
import com.pynapli.shared.auth.StoredSession
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.AuthApi
import com.pynapli.shared.network.ProfileApi
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.AuthUserDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthStateHolderTest {
    @Test
    fun sessionInvalidationDropsBackToSignIn() =
        runBlocking {
            val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val holder =
                AuthStateHolder(
                    authRepository =
                        AuthRepository(
                            authApi = FakeAuthApi(loginResult = AppResult.Success(session())),
                            profileApi = FakeProfileApi(),
                            sessionStore = InMemoryStore(),
                        ),
                    platform = "test",
                    appVersion = "0.0.0",
                    sessionInvalidations = invalidations,
                )
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { holder.observeSessionInvalidations() }

            holder.submitDevLogin(DevLoginCredentials("dev@pynapli.local", "PynapliDevPass123!"))
            assertTrue(holder.state.value.authenticated, "expected a live session after login")

            invalidations.emit(Unit)
            yield() // let the observer apply the state update

            assertFalse(holder.state.value.authenticated, "a terminal invalidation must return to sign-in")
            assertEquals("Your session ended. Please sign in again.", holder.state.value.errorMessage)
            observer.cancel()
        }

    @Test
    fun invalidationWhileSignedOutIsANoOp() =
        runBlocking {
            val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val holder =
                AuthStateHolder(
                    authRepository =
                        AuthRepository(
                            authApi = FakeAuthApi(),
                            profileApi = FakeProfileApi(),
                            sessionStore = InMemoryStore(),
                        ),
                    platform = "test",
                    appVersion = "0.0.0",
                    sessionInvalidations = invalidations,
                )
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { holder.observeSessionInvalidations() }

            invalidations.emit(Unit)
            yield()

            // No spurious error banner appears when there was no session to lose.
            assertFalse(holder.state.value.authenticated)
            assertEquals(null, holder.state.value.errorMessage)
            observer.cancel()
        }

    private fun session(): AuthSessionDto =
        AuthSessionDto(
            accessToken = "access",
            refreshToken = "refresh",
            user = AuthUserDto(userId = "user-1", status = "active"),
        )
}

private class InMemoryStore : SessionStore {
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

private class FakeAuthApi(
    private val loginResult: AppResult<AuthSessionDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "no")),
) : AuthApi {
    override suspend fun emailLogin(request: EmailLoginRequestDto): AppResult<AuthSessionDto> = loginResult

    override suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<AuthSessionDto> = fail()

    override suspend fun refreshSession(request: RefreshSessionRequestDto): AppResult<AuthSessionDto> = fail()

    override suspend fun logout(): AppResult<LogoutResponseDto> = AppResult.Success(LogoutResponseDto(loggedOut = true))

    private fun <T> fail(): AppResult<T> = AppResult.Failure(AppError("UNIMPLEMENTED", "no"))
}

private class FakeProfileApi : ProfileApi {
    override suspend fun getMe(): AppResult<MeDto> = fail()

    override suspend fun updateProfile(request: UpdateProfileRequestDto): AppResult<MeDto> = fail()

    override suspend fun updateDiscoverySettings(request: UpdateDiscoverySettingsRequestDto): AppResult<MeDto> = fail()

    override suspend fun getPublicProfile(userId: String): AppResult<PublicProfileDto> = fail()

    private fun <T> fail(): AppResult<T> = AppResult.Failure(AppError("UNIMPLEMENTED", "no"))
}
