package com.pynapli.app.auth

import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.auth.SessionStore
import com.pynapli.shared.auth.StoredSession
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.AuthApi
import com.pynapli.shared.network.ProfileApi
import com.pynapli.shared.network.dto.AccountAppealRequestDto
import com.pynapli.shared.network.dto.AppealDto
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.AuthUserDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.EmailSignupStartedDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.PasswordChangedDto
import com.pynapli.shared.network.dto.PasswordResetDto
import com.pynapli.shared.network.dto.PasswordResetRequestDto
import com.pynapli.shared.network.dto.PasswordResetRequestedDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthStateHolderTest {
    @Test
    fun aSuspendedAccountCanAppealFromSignIn() =
        runTest {
            val api = FakeAuthApi(loginResult = AppResult.Failure(AppError("ACCOUNT_NOT_ACTIVE", "This account is suspended.")))
            val holder =
                AuthStateHolder(
                    authRepository = AuthRepository(authApi = api, profileApi = FakeProfileApi(), sessionStore = InMemoryStore()),
                    platform = "test",
                    appVersion = "0.0.0",
                )
            holder.updateEmail("kai@pynapli.local")
            holder.updatePassword("kai-password-123")
            holder.submit()
            assertTrue(holder.state.value.accountSuspended)

            holder.updateAppealReason("short")
            holder.submitAccountAppeal()
            assertEquals("Tell us a little about what happened.", holder.state.value.errorMessage)

            holder.updateAppealReason("I was reported by someone I had blocked.")
            holder.submitAccountAppeal()
            assertEquals(listOf("kai@pynapli.local"), api.appeals.map { it.email })
            assertEquals("kai-password-123", api.appeals.single().password)
            assertTrue(holder.state.value.appealSent)
        }

    @Test
    fun aWrongPasswordDoesNotOfferAnAppeal() =
        runTest {
            val api = FakeAuthApi(loginResult = AppResult.Failure(AppError("INVALID_CREDENTIALS", "Invalid email or password.")))
            val holder =
                AuthStateHolder(
                    authRepository = AuthRepository(authApi = api, profileApi = FakeProfileApi(), sessionStore = InMemoryStore()),
                    platform = "test",
                    appVersion = "0.0.0",
                )
            holder.updateEmail("kai@pynapli.local")
            holder.updatePassword("wrong-password-123")
            holder.submit()

            assertFalse(holder.state.value.accountSuspended)
        }

    @Test
    fun forgotPasswordSendsACodeThenSignsInWithTheNewPassword() =
        runTest {
            val api = FakeAuthApi(loginResult = AppResult.Success(session()))
            val holder =
                AuthStateHolder(
                    authRepository = AuthRepository(authApi = api, profileApi = FakeProfileApi(), sessionStore = InMemoryStore()),
                    platform = "test",
                    appVersion = "0.0.0",
                )

            holder.switchMode(AuthMode.ResetPassword)
            holder.updateEmail(" maya@pynapli.local ")
            holder.submit()
            assertTrue(holder.state.value.resetCodeSent)
            assertEquals("maya@pynapli.local", api.resetRequests.single())

            holder.updateResetCode("12-34 56x")
            holder.updatePassword("short")
            holder.submit()
            assertEquals("Password must be at least 12 characters.", holder.state.value.errorMessage)

            holder.updatePassword("a-new-password-123")
            holder.submit()
            assertEquals(PasswordResetDto("maya@pynapli.local", "123456", "a-new-password-123"), api.resets.single())
            assertEquals("a-new-password-123", api.logins.single().password)
            assertTrue(holder.state.value.authenticated)
            assertEquals(AuthMode.SignIn, holder.state.value.mode)
        }

    @Test
    fun aWrongCodeKeepsTheResetScreenWithTheServerMessage() =
        runTest {
            val api =
                FakeAuthApi(
                    loginResult = AppResult.Success(session()),
                    resetResult = AppResult.Failure(AppError("INVALID_RESET_CODE", "That code isn't right or has expired. Request a new one.")),
                )
            val holder =
                AuthStateHolder(
                    authRepository = AuthRepository(authApi = api, profileApi = FakeProfileApi(), sessionStore = InMemoryStore()),
                    platform = "test",
                    appVersion = "0.0.0",
                )
            holder.switchMode(AuthMode.ResetPassword)
            holder.updateEmail("maya@pynapli.local")
            holder.submit()
            holder.updateResetCode("000000")
            holder.updatePassword("a-new-password-123")
            holder.submit()

            assertFalse(holder.state.value.authenticated)
            assertTrue(holder.state.value.resetCodeSent)
            assertEquals("That code isn't right or has expired. Request a new one.", holder.state.value.errorMessage)
            assertTrue(api.logins.isEmpty())
        }

    @Test
    fun sessionInvalidationDropsBackToSignIn() =
        runTest {
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
            runCurrent() // let the observer apply the state update

            assertFalse(holder.state.value.authenticated, "a terminal invalidation must return to sign-in")
            assertEquals("Your session ended. Please sign in again.", holder.state.value.errorMessage)
            observer.cancel()
        }

    @Test
    fun invalidationWhileSignedOutIsANoOp() =
        runTest {
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
            runCurrent()

            // No spurious error banner appears when there was no session to lose.
            assertFalse(holder.state.value.authenticated)
            assertEquals(null, holder.state.value.errorMessage)
            observer.cancel()
        }

    @Test
    fun aSignedOutVisitorStartsOnTheWelcomeLandingInSignInMode() =
        runTest {
            val holder = holder(FakeAuthApi())

            assertTrue(holder.state.value.showWelcome)
            assertEquals(AuthMode.SignIn, holder.state.value.mode)

            // "Sign in" on the landing opens the form; back returns to the landing.
            holder.switchMode(AuthMode.SignIn)
            assertFalse(holder.state.value.showWelcome)
            holder.showWelcome()
            assertTrue(holder.state.value.showWelcome)
        }

    @Test
    fun theSignInScreenNeverStartsASignUp() =
        runTest {
            val api = FakeAuthApi()
            val holder = holder(api)
            holder.switchMode(AuthMode.SignIn)
            holder.updateEmail("new@pynapli.local")
            holder.updatePassword("a-long-password-1")

            holder.submit()

            // Creating an account is the stepper's job (it has the birthday gate and the code step).
            assertEquals(0, api.signups)
            assertEquals(1, api.logins.size)
        }

    @Test
    fun logOutReturnsToTheWelcomeLandingWithNothingLeftOver() =
        runTest {
            val holder = holder(FakeAuthApi(loginResult = AppResult.Success(session())))
            holder.switchMode(AuthMode.ResetPassword)
            holder.updateEmail("maya@pynapli.local")
            holder.submit()
            holder.switchMode(AuthMode.SignIn)
            holder.updatePassword("a-long-password-1")
            holder.submit()
            assertTrue(holder.state.value.authenticated)

            holder.logout()

            val state = holder.state.value
            assertFalse(state.authenticated)
            assertTrue(state.showWelcome)
            assertEquals(AuthMode.SignIn, state.mode)
            assertEquals("", state.password)
            assertFalse(state.resetCodeSent)
            assertEquals(null, state.errorMessage)
        }

    @Test
    fun showSignInOpensTheFormWithTheGivenAddressAndClearsOldErrors() =
        runTest {
            val holder = holder(FakeAuthApi(loginResult = AppResult.Failure(AppError("INVALID_CREDENTIALS", "Invalid email or password."))))
            holder.switchMode(AuthMode.SignIn)
            holder.updateEmail("old@pynapli.local")
            holder.updatePassword("wrong-password-123")
            holder.submit()
            assertEquals("Invalid email or password.", holder.state.value.errorMessage)

            holder.showSignIn(prefillEmail = "typed@pynapli.local")

            val state = holder.state.value
            assertFalse(state.showWelcome)
            assertEquals("typed@pynapli.local", state.email)
            assertEquals("", state.password)
            assertEquals(null, state.errorMessage)
        }

    @Test
    fun aSessionThatDiesLandsOnTheSignInFormNotTheWelcome() =
        runTest {
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

            invalidations.emit(Unit)
            runCurrent()

            // The reason ("Your session ended") must be visible, so not behind the landing.
            assertFalse(holder.state.value.showWelcome)
            assertEquals(AuthMode.SignIn, holder.state.value.mode)
            observer.cancel()
        }

    private fun holder(api: FakeAuthApi): AuthStateHolder =
        AuthStateHolder(
            authRepository = AuthRepository(authApi = api, profileApi = FakeProfileApi(), sessionStore = InMemoryStore()),
            platform = "test",
            appVersion = "0.0.0",
        )

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
    private val resetResult: AppResult<PasswordChangedDto> = AppResult.Success(PasswordChangedDto(changed = true)),
) : AuthApi {
    val logins = mutableListOf<EmailLoginRequestDto>()
    val resetRequests = mutableListOf<String>()
    val resets = mutableListOf<PasswordResetDto>()
    val appeals = mutableListOf<AccountAppealRequestDto>()

    override suspend fun submitAccountAppeal(request: AccountAppealRequestDto): AppResult<AppealDto> {
        appeals += request
        return AppResult.Success(AppealDto("appeal-1", "account_suspension", "account", status = "open", userReason = request.reason, createdAt = "", updatedAt = ""))
    }

    override suspend fun emailLogin(request: EmailLoginRequestDto): AppResult<AuthSessionDto> {
        logins += request
        return loginResult
    }

    override suspend fun requestPasswordReset(request: PasswordResetRequestDto): AppResult<PasswordResetRequestedDto> {
        resetRequests += request.email
        return AppResult.Success(PasswordResetRequestedDto(sent = true))
    }

    override suspend fun resetPassword(request: PasswordResetDto): AppResult<PasswordChangedDto> {
        resets += request
        return resetResult
    }

    var signups = 0
        private set

    override suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<EmailSignupStartedDto> {
        signups++
        return fail()
    }

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
