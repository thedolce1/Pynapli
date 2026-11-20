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
import com.pynapli.shared.network.dto.EmailSignupCodeResentDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.EmailSignupResendRequestDto
import com.pynapli.shared.network.dto.EmailSignupStartedDto
import com.pynapli.shared.network.dto.EmailSignupVerifyRequestDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.ProfileDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import com.pynapli.shared.settings.InMemoryPendingSignupStore
import com.pynapli.shared.settings.OnboardingProgress
import com.pynapli.shared.settings.OnboardingProgressStore
import com.pynapli.shared.settings.PendingSignup
import com.pynapli.shared.settings.maskEmailAddress
import kotlinx.coroutines.test.runTest
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
        runTest {
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
        runTest {
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
        runTest {
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
        runTest {
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
    fun submitAccountStartsTheSignUpAndMovesToTheCodeStepWithoutASession() =
        runTest {
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

            val state = fixture.onboarding.state.value
            assertEquals(OnboardingStep.VerifyEmail, state.step)
            assertEquals("new@pynapli.local", state.email)
            assertEquals("", state.password, "the password is not kept once the sign-up has started")
            // No account and no session until the emailed code checks out.
            assertNull(fixture.auth.state.value.session)
            assertTrue(fixture.progressStore.progress.completed)
            assertEquals(PendingSignup("new@pynapli.local", "signup-token", fixture.nowMillis), fixture.pendingStore.load())
            // Right after sign-up the person typed the address themselves: shown in full.
            assertEquals("new@pynapli.local", state.verifyEmailLabel)
        }

    @Test
    fun theRightCodeCreatesTheSessionAndEntersThePostAuthSteps() =
        runTest {
            val fixture = Fixture()
            fixture.startSignup()

            fixture.onboarding.updateSignupCode("12 34-56x")
            fixture.onboarding.verifySignupCode()

            assertEquals(
                EmailSignupVerifyRequestDto("new@pynapli.local", "123456", "signup-token", null, "test", "0.0.0"),
                fixture.authApi.verifyRequests.single(),
            )
            assertEquals(OnboardingStep.Location, fixture.onboarding.state.value.step)
            assertNotNull(fixture.auth.state.value.session)
            assertFalse(fixture.progressStore.progress.completed)
            assertEquals("user-1", fixture.progressStore.progress.userId)
            assertNull(fixture.pendingStore.load())
        }

    @Test
    fun aShortCodeIsCaughtBeforeTheServer() =
        runTest {
            val fixture = Fixture()
            fixture.startSignup()

            fixture.onboarding.updateSignupCode("123")
            fixture.onboarding.verifySignupCode()

            assertEquals("Enter the 6-digit code from the email.", fixture.onboarding.state.value.errorMessage)
            assertTrue(fixture.authApi.verifyRequests.isEmpty())
        }

    @Test
    fun aWrongOrExpiredCodeStaysOnTheCodeStepAndANewCodeRecovers() =
        runTest {
            val wrongThenRight = ArrayDeque(listOf(false, true))
            val fixture =
                Fixture(
                    authApi =
                        FakeAuthApi(
                            verifyResult = {
                                if (wrongThenRight.removeFirst()) {
                                    AppResult.Success(testSessionDto())
                                } else {
                                    AppResult.Failure(AppError("INVALID_SIGNUP_CODE", "That code isn't right or has expired. Check the newest email, or send a new code."))
                                }
                            },
                        ),
                )
            fixture.startSignup()

            fixture.onboarding.updateSignupCode("000000")
            fixture.onboarding.verifySignupCode()

            val failed = fixture.onboarding.state.value
            assertEquals(OnboardingStep.VerifyEmail, failed.step)
            assertEquals("That code isn't right or has expired. Check the newest email, or send a new code.", failed.errorMessage)
            assertEquals("", failed.signupCode)
            assertNull(fixture.auth.state.value.session)
            assertEquals("signup-token", fixture.pendingStore.load()?.signupToken)

            fixture.onboarding.resendSignupCode()

            assertEquals(EmailSignupResendRequestDto("new@pynapli.local", "signup-token", null), fixture.authApi.resendRequests.single())
            assertEquals(OnboardingStateHolder.RESENT_NOTICE, fixture.onboarding.state.value.signupNotice)
            assertNull(fixture.onboarding.state.value.errorMessage)

            fixture.onboarding.updateSignupCode("654321")
            fixture.onboarding.verifySignupCode()
            assertEquals(OnboardingStep.Location, fixture.onboarding.state.value.step)
        }

    @Test
    fun useADifferentEmailDropsTheStartedSignUp() =
        runTest {
            val fixture = Fixture()
            fixture.startSignup()

            fixture.onboarding.useDifferentEmail()

            assertEquals(OnboardingStep.Account, fixture.onboarding.state.value.step)
            assertNull(fixture.pendingStore.load())
            // Nothing left to verify with: a stray verify restarts instead of calling the server.
            fixture.onboarding.updateSignupCode("123456")
            fixture.onboarding.verifySignupCode()
            assertTrue(fixture.authApi.verifyRequests.isEmpty())
        }

    @Test
    fun anAppKilledBeforeTheCodeReopensTheCodeStepForThatAddress() =
        runTest {
            val fixture =
                Fixture(
                    pendingStore = InMemoryPendingSignupStore(PendingSignup("new@pynapli.local", "stored-token", NOW_MILLIS - 60 * 60 * 1_000)),
                )

            fixture.onboarding.resumePendingSignupIfNeeded()

            val state = fixture.onboarding.state.value
            assertTrue(state.active)
            assertEquals(OnboardingStep.VerifyEmail, state.step)
            assertEquals("new@pynapli.local", state.email)
            assertEquals("", state.password)
            // Whoever opens the app next may not be the person who started: never the full address.
            assertEquals("n•••@p•••.local", state.verifyEmailLabel)

            fixture.onboarding.updateSignupCode("123456")
            fixture.onboarding.verifySignupCode()
            assertEquals(
                "stored-token",
                fixture.authApi.verifyRequests
                    .single()
                    .signupToken,
            )
            assertEquals(OnboardingStep.Location, fixture.onboarding.state.value.step)
        }

    @Test
    fun aSignUpOlderThanTheServerKeepsItRestartsCleanly() =
        runTest {
            val fixture =
                Fixture(
                    pendingStore =
                        InMemoryPendingSignupStore(
                            PendingSignup("new@pynapli.local", "stored-token", NOW_MILLIS - OnboardingStateHolder.PENDING_SIGNUP_LIFETIME_MILLIS - 1),
                        ),
                )

            fixture.onboarding.resumePendingSignupIfNeeded()

            assertFalse(fixture.onboarding.state.value.active)
            assertNull(fixture.pendingStore.load())
        }

    @Test
    fun signingInAnotherWayDropsAWaitingSignUp() =
        runTest {
            val fixture =
                Fixture(
                    pendingStore = InMemoryPendingSignupStore(PendingSignup("new@pynapli.local", "stored-token", NOW_MILLIS)),
                )
            fixture.signIn()

            fixture.onboarding.resumePendingSignupIfNeeded()
            assertFalse(fixture.onboarding.state.value.active, "a signed-in device never reopens the code step")
            fixture.onboarding.resumeIfNeeded()

            assertFalse(fixture.onboarding.state.value.active)
            assertNull(fixture.pendingStore.load())
        }

    @Test
    fun submitAccountFailureSurfacesServerErrorAndStaysOnAccount() =
        runTest {
            val fixture =
                Fixture(
                    authApi =
                        FakeAuthApi(
                            signupResult = {
                                AppResult.Failure(AppError("RATE_LIMITED", "Too many actions. Try again later."))
                            },
                        ),
                )
            fixture.advanceToAccount()
            fixture.onboarding.updateEmail("new@pynapli.local")
            fixture.onboarding.updatePassword("LongEnoughPassword1!")

            fixture.onboarding.submitAccount()

            val state = fixture.onboarding.state.value
            assertEquals(OnboardingStep.Account, state.step)
            assertEquals("Too many actions. Try again later.", state.errorMessage)
            assertFalse(state.isSubmitting)
            // Failed signup must not arm either resume flow.
            assertTrue(fixture.progressStore.progress.completed)
            assertNull(fixture.pendingStore.load())
        }

    @Test
    fun finishProfileWithBlankNameSkipsTheApiAndCompletes() =
        runTest {
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
        runTest {
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
        runTest {
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
        runTest {
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
        runTest {
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
    fun afterFiveWrongCodesTheCodeStepOffersStartAgainForTheSameAddress() =
        runTest {
            val fixture =
                Fixture(
                    authApi = FakeAuthApi(verifyResult = { AppResult.Failure(AppError("INVALID_SIGNUP_CODE", "That code isn't right or has expired.")) }),
                )
            fixture.startSignup()

            repeat(OnboardingStateHolder.CODE_ATTEMPTS_PER_SIGNUP) {
                fixture.onboarding.updateSignupCode("000000")
                fixture.onboarding.verifySignupCode()
            }

            val spent = fixture.onboarding.state.value
            assertTrue(spent.signupAttemptsSpent)
            assertEquals(OnboardingStateHolder.ATTEMPTS_SPENT_MESSAGE, spent.errorMessage)
            assertEquals(OnboardingStateHolder.CODE_ATTEMPTS_PER_SIGNUP, fixture.authApi.verifyRequests.size)
            // Nothing more goes to the server for this sign-up: no sixth guess, no pointless new code.
            fixture.onboarding.updateSignupCode("123456")
            fixture.onboarding.verifySignupCode()
            fixture.onboarding.resendSignupCode()
            assertEquals(OnboardingStateHolder.CODE_ATTEMPTS_PER_SIGNUP, fixture.authApi.verifyRequests.size)
            assertTrue(fixture.authApi.resendRequests.isEmpty())

            fixture.onboarding.startSignupAgain()

            val again = fixture.onboarding.state.value
            assertEquals(OnboardingStep.Account, again.step)
            assertEquals("new@pynapli.local", again.email)
            assertEquals("", again.password)
            assertFalse(again.signupAttemptsSpent)
            assertEquals(OnboardingStateHolder.START_AGAIN_NOTICE, again.accountNotice)
            assertNull(fixture.pendingStore.load())

            fixture.onboarding.updatePassword("LongEnoughPassword1!")
            fixture.onboarding.submitAccount()
            assertEquals("new@pynapli.local", fixture.authApi.lastSignupRequest?.email)
            assertEquals(OnboardingStep.VerifyEmail, fixture.onboarding.state.value.step)
            assertFalse(fixture.onboarding.state.value.signupAttemptsSpent)
        }

    @Test
    fun withNoEmailEverArrivingStartAgainIsThereFromTheCodeStep() =
        runTest {
            val fixture = Fixture()
            fixture.startSignup()
            // The email work may have been dropped: a new code can't help, a new sign-up can.
            fixture.onboarding.resendSignupCode()
            assertEquals(OnboardingStateHolder.RESENT_NOTICE, fixture.onboarding.state.value.signupNotice)

            fixture.onboarding.startSignupAgain()

            assertEquals(OnboardingStep.Account, fixture.onboarding.state.value.step)
            assertEquals("new@pynapli.local", fixture.onboarding.state.value.email)
            assertNull(fixture.pendingStore.load())
        }

    @Test
    fun backToSignInDropsTheWaitingSignUp() =
        runTest {
            val fixture = Fixture()
            fixture.startSignup()
            assertNotNull(fixture.pendingStore.load())

            fixture.onboarding.cancelToSignIn()

            assertFalse(fixture.onboarding.state.value.active)
            assertNull(fixture.pendingStore.load())
            // The next launch does not reopen the code step.
            fixture.onboarding.resumePendingSignupIfNeeded()
            assertFalse(fixture.onboarding.state.value.active)
        }

    @Test
    fun theResumedAddressIsMasked() {
        assertEquals("j•••@g•••.com", maskEmailAddress("jane.doe@gmail.com"))
        assertEquals("a•••@e•••.co", maskEmailAddress("a@example.co"))
        assertEquals("•••", maskEmailAddress("not-an-address"))
    }

    @Test
    fun photoNudgeEligibilityIsBounded() =
        runTest {
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
        runTest {
            val fixture = Fixture()
            fixture.onboarding.recordPhotoNudgeShown()
            fixture.onboarding.recordPhotoNudgeShown()
            assertEquals(1, fixture.progressStore.progress.photoNudgeSessionsSeen)
        }

    @Test
    fun photoNudgeDismissPersistsAndHidesImmediately() =
        runTest {
            val fixture = Fixture()
            fixture.onboarding.evaluatePhotoNudge()
            assertTrue(fixture.onboarding.state.value.photoNudgeEligible)

            fixture.onboarding.dismissPhotoNudge()

            assertFalse(fixture.onboarding.state.value.photoNudgeEligible)
            assertTrue(fixture.progressStore.progress.photoNudgeDismissed)
        }

    private inner class Fixture(
        val progressStore: InMemoryProgressStore = InMemoryProgressStore(),
        val pendingStore: InMemoryPendingSignupStore = InMemoryPendingSignupStore(),
        val nowMillis: Long = NOW_MILLIS,
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
                nowEpochMillis = { nowMillis },
                pendingSignupStore = pendingStore,
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

        /** Drives the stepper to the code step for new@pynapli.local. */
        suspend fun startSignup() {
            advanceToAccount()
            onboarding.updateEmail("new@pynapli.local")
            onboarding.updatePassword("LongEnoughPassword1!")
            onboarding.submitAccount()
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

private const val NOW_MILLIS = 1_781_000_000_000L

private class FakeAuthApi(
    private val signupResult: () -> AppResult<EmailSignupStartedDto> = { AppResult.Success(EmailSignupStartedDto(signupToken = "signup-token")) },
    private val verifyResult: (EmailSignupVerifyRequestDto) -> AppResult<AuthSessionDto> = { AppResult.Success(testSessionDto()) },
) : AuthApi {
    var lastSignupRequest: EmailSignupRequestDto? = null
        private set
    val verifyRequests = mutableListOf<EmailSignupVerifyRequestDto>()
    val resendRequests = mutableListOf<EmailSignupResendRequestDto>()

    override suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<EmailSignupStartedDto> {
        lastSignupRequest = request
        return signupResult()
    }

    override suspend fun verifyEmailSignup(request: EmailSignupVerifyRequestDto): AppResult<AuthSessionDto> {
        verifyRequests += request
        return verifyResult(request)
    }

    override suspend fun resendEmailSignupCode(request: EmailSignupResendRequestDto): AppResult<EmailSignupCodeResentDto> {
        resendRequests += request
        return AppResult.Success(EmailSignupCodeResentDto(sent = true))
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
