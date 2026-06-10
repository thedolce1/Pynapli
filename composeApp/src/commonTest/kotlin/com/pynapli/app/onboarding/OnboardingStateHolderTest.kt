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
    fun confirmDobValidatesTheTypedDate() =
        runBlocking {
            val fixture = Fixture()
            fixture.onboarding.startCreateAccount()

            // Impossible calendar date.
            fixture.enterDob(day = "31", month = "02", year = "2001")
            fixture.onboarding.confirmDob()
            assertEquals("Enter a valid date of birth.", fixture.onboarding.state.value.errorMessage)
            assertEquals(OnboardingStep.Dob, fixture.onboarding.state.value.step)

            // Future date.
            fixture.enterDob(day = "01", month = "01", year = "2030")
            fixture.onboarding.confirmDob()
            assertEquals("Enter a valid date of birth.", fixture.onboarding.state.value.errorMessage)

            // Valid leap day proceeds.
            fixture.enterDob(day = "29", month = "02", year = "2000")
            fixture.onboarding.confirmDob()
            assertEquals(OnboardingStep.Policies, fixture.onboarding.state.value.step)
            assertEquals(LocalDate(2000, 2, 29), fixture.onboarding.state.value.dob)
        }

    @Test
    fun confirmDobStopsUnderageBeforeAccountCreation() =
        runBlocking {
            val fixture = Fixture()
            fixture.onboarding.startCreateAccount()

            // One day short of 18.
            fixture.enterDob(day = "11", month = "06", year = "2008")
            fixture.onboarding.confirmDob()
            assertEquals(OnboardingStep.UnderageStop, fixture.onboarding.state.value.step)

            // Exactly 18 today passes.
            fixture.onboarding.backToDob()
            fixture.enterDob(day = "10", month = "06", year = "2008")
            fixture.onboarding.confirmDob()
            assertEquals(OnboardingStep.Policies, fixture.onboarding.state.value.step)
        }

    @Test
    fun dobFieldsKeepDigitsOnlyAndClampLength() {
        val fixture = Fixture()
        fixture.onboarding.updateDobDay("3a1x9")
        fixture.onboarding.updateDobMonth("0/6")
        fixture.onboarding.updateDobYear("19789")

        val state = fixture.onboarding.state.value
        assertEquals("31", state.dobDay)
        assertEquals("06", state.dobMonth)
        assertEquals("1978", state.dobYear)
    }

    @Test
    fun parseDobIsCalendarStrict() {
        assertNull(OnboardingStateHolder.parseDob("31", "04", "2000", today))
        assertNull(OnboardingStateHolder.parseDob("29", "02", "2001", today))
        assertNull(OnboardingStateHolder.parseDob("29", "02", "1900", today))
        assertNotNull(OnboardingStateHolder.parseDob("29", "02", "2000", today))
        assertNull(OnboardingStateHolder.parseDob("01", "13", "2000", today))
        assertNull(OnboardingStateHolder.parseDob("00", "01", "2000", today))
        // Year must be four typed digits within a sane window.
        assertNull(OnboardingStateHolder.parseDob("01", "01", "200", today))
        assertNull(OnboardingStateHolder.parseDob("01", "01", "1899", today))
        assertNull(OnboardingStateHolder.parseDob("11", "06", "2026", today))
        assertNotNull(OnboardingStateHolder.parseDob("10", "06", "2026", today))
    }

    @Test
    fun acceptPoliciesRecordsBothAcceptancesAndAdvances() =
        runBlocking {
            val fixture = Fixture()
            fixture.onboarding.startCreateAccount()
            fixture.enterDob(day = "01", month = "06", year = "2000")
            fixture.onboarding.confirmDob()

            fixture.onboarding.acceptPolicies()

            val state = fixture.onboarding.state.value
            assertEquals(OnboardingStep.Account, state.step)
            assertTrue(state.acceptedTerms)
            assertTrue(state.acceptedPrivacy)
        }

    @Test
    fun submitAccountValidatesBeforeCallingTheApi() =
        runBlocking {
            val fixture = Fixture()
            fixture.advanceToAccount()

            fixture.onboarding.updateEmail("not-an-email")
            fixture.onboarding.updatePassword("LongEnoughPassword1!")
            fixture.onboarding.submitAccount()
            assertEquals("Enter a valid email address.", fixture.onboarding.state.value.errorMessage)

            fixture.onboarding.updateEmail("new@pynapli.local")
            fixture.onboarding.updatePassword("short")
            fixture.onboarding.submitAccount()
            assertEquals(
                "Password must be at least ${OnboardingStateHolder.MIN_PASSWORD_LENGTH} characters.",
                fixture.onboarding.state.value.errorMessage,
            )

            assertNull(fixture.authApi.lastSignupRequest)
            assertEquals(OnboardingStep.Account, fixture.onboarding.state.value.step)
        }

    @Test
    fun submitAccountSuccessEntersPostAuthStepsAndMarksInProgress() =
        runBlocking {
            val fixture = Fixture()
            fixture.advanceToAccount(day = "29", month = "02", year = "2000")
            fixture.onboarding.updateEmail(" new@pynapli.local ")
            fixture.onboarding.updatePassword("LongEnoughPassword1!")

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
            fixture.advanceToAccount()
            fixture.onboarding.updateEmail("taken@pynapli.local")
            fixture.onboarding.updatePassword("LongEnoughPassword1!")

            fixture.onboarding.submitAccount()

            val state = fixture.onboarding.state.value
            assertEquals(OnboardingStep.Account, state.step)
            assertEquals("Email already registered.", state.errorMessage)
            assertFalse(state.isSubmitting)
            // Failed signup must not arm the resume flow.
            assertTrue(fixture.progressStore.progress.completed)
        }

    @Test
    fun finishProfileWithBlankNameSkipsTheApiAndCompletes() =
        runBlocking {
            val fixture =
                Fixture(
                    progressStore =
                        InMemoryProgressStore(
                            OnboardingProgress(completed = false, userId = "user-1"),
                        ),
                )
            fixture.onboarding.startCreateAccount()

            fixture.onboarding.finishProfile()

            assertNull(fixture.profileRepository.lastDisplayName)
            assertFalse(fixture.onboarding.state.value.active)
            assertTrue(fixture.progressStore.progress.completed)
            assertNull(fixture.progressStore.progress.userId)
        }

    @Test
    fun finishProfileSavesTypedNameThenCompletes() =
        runBlocking {
            val fixture =
                Fixture(
                    progressStore =
                        InMemoryProgressStore(
                            OnboardingProgress(completed = false, userId = "user-1"),
                        ),
                )
            fixture.onboarding.startCreateAccount()
            fixture.onboarding.updateDisplayName(" Alex ")

            fixture.onboarding.finishProfile()

            assertEquals("Alex", fixture.profileRepository.lastDisplayName)
            assertFalse(fixture.onboarding.state.value.active)
            assertTrue(fixture.progressStore.progress.completed)
        }

    @Test
    fun finishProfileKeepsTheStepperOnSaveFailure() =
        runBlocking {
            val fixture =
                Fixture(
                    progressStore =
                        InMemoryProgressStore(
                            OnboardingProgress(completed = false, userId = "user-1"),
                        ),
                    profileRepository =
                        FakeOnboardingProfileRepository(
                            updateDisplayNameResult = { AppResult.Failure(AppError("NETWORK", "Offline.")) },
                        ),
                )
            fixture.onboarding.startCreateAccount()
            fixture.onboarding.updateDisplayName("Alex")

            fixture.onboarding.finishProfile()

            assertTrue(fixture.onboarding.state.value.active)
            assertEquals("Offline.", fixture.onboarding.state.value.errorMessage)
            assertFalse(fixture.progressStore.progress.completed)
        }

    @Test
    fun displayNameIsCappedAtFifteenCharacters() {
        val fixture = Fixture()
        fixture.onboarding.updateDisplayName("ABCDEFGHIJKLMNOPQRST")
        assertEquals("ABCDEFGHIJKLMNO", fixture.onboarding.state.value.displayName)
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
    fun resumeReentersTheProfileStepWithPrefilledName() =
        runBlocking {
            val armedProgress = OnboardingProgress(completed = false, userId = "user-1")

            val namedAlready =
                Fixture(
                    progressStore = InMemoryProgressStore(armedProgress),
                    profileRepository =
                        FakeOnboardingProfileRepository(getMeResult = { AppResult.Success(meDto("Alex")) }),
                )
            namedAlready.signIn()
            namedAlready.onboarding.resumeIfNeeded()
            assertTrue(namedAlready.onboarding.state.value.active)
            assertEquals(OnboardingStep.Profile, namedAlready.onboarding.state.value.step)
            assertEquals("Alex", namedAlready.onboarding.state.value.displayName)

            // Offline profile fetch -> still resumes with an empty prefill.
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
            assertEquals(OnboardingStep.Profile, offline.onboarding.state.value.step)
            assertEquals("", offline.onboarding.state.value.displayName)
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

            // Mid-stepper session: the user just saw (or will see) the profile step.
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

        fun enterDob(
            day: String,
            month: String,
            year: String,
        ) {
            onboarding.updateDobDay(day)
            onboarding.updateDobMonth(month)
            onboarding.updateDobYear(year)
        }

        /** Drives the pre-auth steps to the Account form. */
        fun advanceToAccount(
            day: String = "01",
            month: String = "06",
            year: String = "2000",
        ) {
            onboarding.startCreateAccount()
            enterDob(day, month, year)
            onboarding.confirmDob()
            onboarding.acceptPolicies()
        }

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
