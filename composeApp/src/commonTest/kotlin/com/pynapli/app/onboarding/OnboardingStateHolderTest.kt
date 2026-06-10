package com.pynapli.app.onboarding

import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.auth.SessionStore
import com.pynapli.shared.auth.StoredSession
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.AuthApi
import com.pynapli.shared.network.ProfileApi
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.AuthUserDto
import com.pynapli.shared.network.dto.DiscoverySettingsDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.ProfileDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import com.pynapli.shared.settings.OnboardingProgress
import com.pynapli.shared.settings.OnboardingProgressStore
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingStateHolderTest {
    private val today = LocalDate(2026, 6, 10)

    @Test
    fun confirmDobStopsUnderageBeforeAccountCreation() =
        runBlocking {
            val fixture = Fixture()
            fixture.onboarding.startCreateAccount()

            // One day short of 18.
            fixture.onboarding.updateDob(LocalDate(2008, 6, 11))
            fixture.onboarding.confirmDob()
            assertEquals(OnboardingStep.UnderageStop, fixture.onboarding.state.value.step)

            // Exactly 18 today passes.
            fixture.onboarding.backToDob()
            fixture.onboarding.updateDob(LocalDate(2008, 6, 10))
            fixture.onboarding.confirmDob()
            assertEquals(OnboardingStep.Account, fixture.onboarding.state.value.step)
        }

    @Test
    fun ageGateIsCalendarCorrectAcrossMonthBoundaries() {
        // Birthday later this year, month not yet reached.
        assertFalse(LocalDate(2008, 7, 1).isAtLeastYearsOld(18, today))
        // Birthday month passed already this year.
        assertTrue(LocalDate(2008, 5, 31).isAtLeastYearsOld(18, today))
        // Older than the threshold by years.
        assertTrue(LocalDate(1990, 12, 31).isAtLeastYearsOld(18, today))
        // Younger by years.
        assertFalse(LocalDate(2020, 1, 1).isAtLeastYearsOld(18, today))
    }

    @Test
    fun submitAccountValidatesBeforeCallingTheApi() =
        runBlocking {
            val fixture = Fixture()
            fixture.onboarding.startCreateAccount()
            fixture.onboarding.updateDob(LocalDate(2000, 1, 1))
            fixture.onboarding.confirmDob()

            fixture.onboarding.updateEmail("not-an-email")
            fixture.onboarding.updatePassword("LongEnoughPassword1!")
            fixture.onboarding.updateAcceptedTerms(true)
            fixture.onboarding.updateAcceptedPrivacy(true)
            fixture.onboarding.submitAccount()
            assertEquals("Enter a valid email address.", fixture.onboarding.state.value.errorMessage)

            fixture.onboarding.updateEmail("new@pynapli.local")
            fixture.onboarding.updatePassword("short")
            fixture.onboarding.submitAccount()
            assertEquals(
                "Password must be at least ${OnboardingStateHolder.MIN_PASSWORD_LENGTH} characters.",
                fixture.onboarding.state.value.errorMessage,
            )

            fixture.onboarding.updatePassword("LongEnoughPassword1!")
            fixture.onboarding.updateAcceptedPrivacy(false)
            fixture.onboarding.submitAccount()
            assertEquals(
                "Accept the Terms and Privacy Policy to continue.",
                fixture.onboarding.state.value.errorMessage,
            )

            assertNull(fixture.authApi.lastSignupRequest)
            assertEquals(OnboardingStep.Account, fixture.onboarding.state.value.step)
        }

    @Test
    fun submitAccountSuccessEntersPostAuthStepsAndMarksInProgress() =
        runBlocking {
            val fixture = Fixture()
            fixture.onboarding.startCreateAccount()
            fixture.onboarding.updateDob(LocalDate(2000, 2, 29))
            fixture.onboarding.confirmDob()
            fixture.onboarding.updateEmail(" new@pynapli.local ")
            fixture.onboarding.updatePassword("LongEnoughPassword1!")
            fixture.onboarding.updateAcceptedTerms(true)
            fixture.onboarding.updateAcceptedPrivacy(true)

            fixture.onboarding.submitAccount()

            val request = assertNotNull(fixture.authApi.lastSignupRequest)
            assertEquals("new@pynapli.local", request.email)
            assertEquals("2000-02-29", request.birthdate)
            assertTrue(request.confirmedAdult)
            assertTrue(request.acceptedTerms)
            assertTrue(request.acceptedPrivacy)

            assertEquals(OnboardingStep.Location, fixture.onboarding.state.value.step)
            assertEquals("", fixture.onboarding.state.value.password)
            assertNotNull(fixture.auth.state.value.session)
            assertFalse(fixture.progressStore.progress.completed)
            assertEquals("user-1", fixture.progressStore.progress.userId)
        }

    @Test
    fun submitAccountFailureSurfacesServerErrorAndStaysOnAccount() =
        runBlocking {
            val fixture =
                Fixture(
                    authApi =
                        FakeAuthApi(
                            signupResult = {
                                AppResult.Failure(AppError("EMAIL_TAKEN", "Email already registered."))
                            },
                        ),
                )
            fixture.onboarding.startCreateAccount()
            fixture.onboarding.updateDob(LocalDate(2000, 1, 1))
            fixture.onboarding.confirmDob()
            fixture.onboarding.updateEmail("taken@pynapli.local")
            fixture.onboarding.updatePassword("LongEnoughPassword1!")
            fixture.onboarding.updateAcceptedTerms(true)
            fixture.onboarding.updateAcceptedPrivacy(true)

            fixture.onboarding.submitAccount()

            val state = fixture.onboarding.state.value
            assertEquals(OnboardingStep.Account, state.step)
            assertEquals("Email already registered.", state.errorMessage)
            assertFalse(state.isSubmitting)
            // Failed signup must not arm the resume flow.
            assertTrue(fixture.progressStore.progress.completed)
        }

    @Test
    fun saveDisplayNameAdvancesToPhotoAndBlankIsRejected() =
        runBlocking {
            val fixture = Fixture()
            fixture.onboarding.startCreateAccount()

            fixture.onboarding.updateDisplayName("   ")
            fixture.onboarding.saveDisplayName()
            assertEquals(
                "Enter a display name, or skip for now.",
                fixture.onboarding.state.value.errorMessage,
            )
            assertNull(fixture.profileRepository.lastDisplayName)

            fixture.onboarding.updateDisplayName(" Alex ")
            fixture.onboarding.saveDisplayName()
            assertEquals("Alex", fixture.profileRepository.lastDisplayName)
            assertEquals(OnboardingStep.Photo, fixture.onboarding.state.value.step)
        }

    @Test
    fun finishCompletesOnboardingAndReleasesTheStepper() =
        runBlocking {
            val fixture =
                Fixture(
                    progressStore =
                        InMemoryProgressStore(
                            OnboardingProgress(completed = false, userId = "user-1"),
                        ),
                )
            fixture.onboarding.startCreateAccount()

            fixture.onboarding.finish()

            assertFalse(fixture.onboarding.state.value.active)
            assertTrue(fixture.progressStore.progress.completed)
            assertNull(fixture.progressStore.progress.userId)
        }

    @Test
    fun resumeSkipsCompletedSignedOutAndForeignAccounts() =
        runBlocking {
            // Completed onboarding: no resume even with a session.
            val completed = Fixture()
            completed.signIn()
            completed.onboarding.resumeIfNeeded()
            assertFalse(completed.onboarding.state.value.active)

            // No session: no resume even when the flag is armed.
            val signedOut =
                Fixture(
                    progressStore =
                        InMemoryProgressStore(
                            OnboardingProgress(completed = false, userId = "user-1"),
                        ),
                )
            signedOut.onboarding.resumeIfNeeded()
            assertFalse(signedOut.onboarding.state.value.active)

            // Different account on the same device: the armed flag is not theirs.
            val foreign =
                Fixture(
                    progressStore =
                        InMemoryProgressStore(
                            OnboardingProgress(completed = false, userId = "someone-else"),
                        ),
                )
            foreign.signIn()
            foreign.onboarding.resumeIfNeeded()
            assertFalse(foreign.onboarding.state.value.active)
        }

    @Test
    fun resumeReentersAtFirstIncompleteStep() =
        runBlocking {
            val armedProgress = OnboardingProgress(completed = false, userId = "user-1")

            // Blank display name -> Name step.
            val blankName =
                Fixture(
                    progressStore = InMemoryProgressStore(armedProgress),
                    profileRepository =
                        FakeOnboardingProfileRepository(getMeResult = { AppResult.Success(meDto(null)) }),
                )
            blankName.signIn()
            blankName.onboarding.resumeIfNeeded()
            assertTrue(blankName.onboarding.state.value.active)
            assertEquals(OnboardingStep.Name, blankName.onboarding.state.value.step)

            // Name already saved -> Photo step.
            val namedAlready =
                Fixture(
                    progressStore = InMemoryProgressStore(armedProgress),
                    profileRepository =
                        FakeOnboardingProfileRepository(getMeResult = { AppResult.Success(meDto("Alex")) }),
                )
            namedAlready.signIn()
            namedAlready.onboarding.resumeIfNeeded()
            assertEquals(OnboardingStep.Photo, namedAlready.onboarding.state.value.step)
            assertEquals("Alex", namedAlready.onboarding.state.value.displayName)

            // Offline profile fetch -> safe fallback to the first post-auth step.
            val offline =
                Fixture(
                    progressStore = InMemoryProgressStore(armedProgress),
                    profileRepository =
                        FakeOnboardingProfileRepository(
                            getMeResult = { AppResult.Failure(AppError("NETWORK", "Offline.")) },
                        ),
                )
            offline.signIn()
            offline.onboarding.resumeIfNeeded()
            assertEquals(OnboardingStep.Name, offline.onboarding.state.value.step)
        }

    @Test
    fun photoNudgeEligibilityIsBounded() =
        runBlocking {
            // Fresh completed account: eligible.
            val fresh = Fixture()
            fresh.onboarding.evaluatePhotoNudge()
            assertTrue(fresh.onboarding.state.value.photoNudgeEligible)

            // Dismissed: never again.
            val dismissed = Fixture(progressStore = InMemoryProgressStore(OnboardingProgress(photoNudgeDismissed = true)))
            dismissed.onboarding.evaluatePhotoNudge()
            assertFalse(dismissed.onboarding.state.value.photoNudgeEligible)

            // Session cap reached.
            val capped =
                Fixture(
                    progressStore =
                        InMemoryProgressStore(
                            OnboardingProgress(photoNudgeSessionsSeen = OnboardingStateHolder.MAX_PHOTO_NUDGE_SESSIONS),
                        ),
                )
            capped.onboarding.evaluatePhotoNudge()
            assertFalse(capped.onboarding.state.value.photoNudgeEligible)

            // Mid-stepper session: the user just saw (or will see) the photo step.
            val midStepper =
                Fixture(
                    progressStore =
                        InMemoryProgressStore(
                            OnboardingProgress(completed = false, userId = "user-1"),
                        ),
                )
            midStepper.onboarding.evaluatePhotoNudge()
            assertFalse(midStepper.onboarding.state.value.photoNudgeEligible)
        }

    @Test
    fun photoNudgeImpressionCountsOncePerProcessSession() =
        runBlocking {
            val fixture = Fixture()
            fixture.onboarding.recordPhotoNudgeShown()
            fixture.onboarding.recordPhotoNudgeShown()
            assertEquals(1, fixture.progressStore.progress.photoNudgeSessionsSeen)
        }

    @Test
    fun photoNudgeDismissPersistsAndHidesImmediately() =
        runBlocking {
            val fixture = Fixture()
            fixture.onboarding.evaluatePhotoNudge()
            assertTrue(fixture.onboarding.state.value.photoNudgeEligible)

            fixture.onboarding.dismissPhotoNudge()

            assertFalse(fixture.onboarding.state.value.photoNudgeEligible)
            assertTrue(fixture.progressStore.progress.photoNudgeDismissed)
        }

    @Test
    fun defaultDobIsAPlausibleAdultNotTheBoundary() {
        val default = OnboardingStateHolder.defaultDob(today)
        assertEquals(LocalDate(2001, 6, 1), default)
    }

    private inner class Fixture(
        val progressStore: InMemoryProgressStore = InMemoryProgressStore(),
        val authApi: FakeAuthApi = FakeAuthApi(),
        val profileRepository: FakeOnboardingProfileRepository = FakeOnboardingProfileRepository(),
        val sessionStore: InMemorySessionStore = InMemorySessionStore(),
    ) {
        val auth =
            AuthStateHolder(
                authRepository =
                    AuthRepository(
                        authApi = authApi,
                        profileApi = FakeProfileApi(),
                        sessionStore = sessionStore,
                    ),
                platform = "test",
                appVersion = "0.0.0",
            )
        val onboarding =
            OnboardingStateHolder(
                authStateHolder = auth,
                profileRepository = profileRepository,
                progressStore = progressStore,
                todayProvider = { today },
            )

        /** Puts a live session into the auth state without going through signup. */
        suspend fun signIn(userId: String = "user-1") {
            sessionStore.save(testSessionDto(userId))
            auth.restoreSession()
        }
    }
}

private fun testSessionDto(userId: String = "user-1") =
    AuthSessionDto(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        user = AuthUserDto(userId = userId, status = "active"),
    )

private fun meDto(displayName: String?) =
    MeDto(
        user = AuthUserDto(userId = "user-1", status = "active"),
        profile =
            ProfileDto(
                userId = "user-1",
                displayName = displayName,
                discoveryStyle = "solo",
                profileCompletenessScore = 10,
                moderationStatus = "approved",
                visibilityStatus = "visible",
            ),
        discoverySettings =
            DiscoverySettingsDto(
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
                socialEnabled = false,
            ),
    )

private val unimplemented = AppError("UNIMPLEMENTED", "Not implemented.")

private class FakeAuthApi(
    private val signupResult: () -> AppResult<AuthSessionDto> = { AppResult.Success(testSessionDto()) },
) : AuthApi {
    var lastSignupRequest: EmailSignupRequestDto? = null
        private set

    override suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<AuthSessionDto> {
        lastSignupRequest = request
        return signupResult()
    }

    override suspend fun emailLogin(request: EmailLoginRequestDto): AppResult<AuthSessionDto> = AppResult.Failure(unimplemented)

    override suspend fun refreshSession(request: RefreshSessionRequestDto): AppResult<AuthSessionDto> = AppResult.Failure(unimplemented)

    override suspend fun logout(): AppResult<LogoutResponseDto> = AppResult.Success(LogoutResponseDto(loggedOut = true))
}

private class FakeProfileApi : ProfileApi {
    override suspend fun getMe(): AppResult<MeDto> = AppResult.Failure(unimplemented)

    override suspend fun updateProfile(request: UpdateProfileRequestDto): AppResult<MeDto> = AppResult.Failure(unimplemented)

    override suspend fun updateDiscoverySettings(request: UpdateDiscoverySettingsRequestDto): AppResult<MeDto> = AppResult.Failure(unimplemented)

    override suspend fun getPublicProfile(userId: String): AppResult<PublicProfileDto> = AppResult.Failure(unimplemented)
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

private class InMemoryProgressStore(
    var progress: OnboardingProgress = OnboardingProgress(),
) : OnboardingProgressStore {
    override suspend fun load(): OnboardingProgress = progress

    override suspend fun save(progress: OnboardingProgress) {
        this.progress = progress
    }
}

private class FakeOnboardingProfileRepository(
    private val updateDisplayNameResult: (String) -> AppResult<MeDto> = { AppResult.Success(meDto(it)) },
    private val getMeResult: () -> AppResult<MeDto> = { AppResult.Success(meDto(null)) },
) : OnboardingProfileRepository {
    var lastDisplayName: String? = null
        private set

    override suspend fun updateDisplayName(displayName: String): AppResult<MeDto> {
        lastDisplayName = displayName
        return updateDisplayNameResult(displayName)
    }

    override suspend fun getMe(): AppResult<MeDto> = getMeResult()
}
