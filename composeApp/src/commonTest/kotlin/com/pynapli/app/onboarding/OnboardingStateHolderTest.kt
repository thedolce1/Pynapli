package com.pynapli.app.onboarding

import com.pynapli.app.auth.AuthMode
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.auth.PasswordRules
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
import com.pynapli.shared.settings.InMemoryUnderageStopStore
import com.pynapli.shared.settings.OnboardingProgress
import com.pynapli.shared.settings.OnboardingProgressStore
import com.pynapli.shared.settings.PendingSignup
import com.pynapli.shared.settings.maskEmailAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
            // The typed birthday is not kept on the stop screen.
            assertEquals("", fixture.onboarding.state.value.dobYear)

            // Exactly 18 today passes (a separate device: the stop above is sticky).
            val adult = Fixture()
            adult.onboarding.startCreateAccount()
            adult.enterDob(day = "10", month = "06", year = "2008")
            adult.onboarding.confirmDob()
            assertEquals(OnboardingStep.Policies, adult.onboarding.state.value.step)
        }

    @Test
    fun theUnderageStopIsStickyForADayButSignInStaysOpen() =
        runTest {
            val fixture = Fixture()
            fixture.onboarding.startCreateAccount()
            fixture.enterDob(day = "01", month = "01", year = "2015")
            fixture.onboarding.confirmDob()
            assertEquals(NOW_MILLIS, fixture.underageStore.load())

            // "Go back" / system back leaves the stepper; it never reopens the birthday.
            fixture.onboarding.backToDob()
            assertEquals(OnboardingStep.UnderageStop, fixture.onboarding.state.value.step)
            assertTrue(fixture.onboarding.stepBack())
            assertFalse(fixture.onboarding.state.value.active)

            // Create account goes straight back to the stop.
            fixture.onboarding.startCreateAccount()
            assertEquals(OnboardingStep.UnderageStop, fixture.onboarding.state.value.step)

            // Sign in stays available.
            fixture.onboarding.cancelToSignIn()
            assertFalse(fixture.onboarding.state.value.active)
            assertFalse(fixture.auth.state.value.showWelcome)

            // A day later the birthday step is back.
            fixture.clockMillis += OnboardingStateHolder.UNDERAGE_STOP_MILLIS
            fixture.onboarding.startCreateAccount()
            assertEquals(OnboardingStep.Dob, fixture.onboarding.state.value.step)
        }

    @Test
    fun theUnderageStopSurvivesARestartUntilItExpires() =
        runTest {
            // A new process on the same device: the stored stop is read at the signed-out settle.
            val fixture = Fixture(underageStore = InMemoryUnderageStopStore(NOW_MILLIS - 60 * 60 * 1_000))
            fixture.onboarding.resumePendingSignupIfNeeded()
            fixture.onboarding.startCreateAccount()
            assertEquals(OnboardingStep.UnderageStop, fixture.onboarding.state.value.step)

            // Create account tapped (or a /signup link opened) before the read finished: moved on read.
            val early = Fixture(underageStore = InMemoryUnderageStopStore(NOW_MILLIS - 1_000))
            early.onboarding.startCreateAccount()
            assertEquals(OnboardingStep.Dob, early.onboarding.state.value.step)
            early.onboarding.loadUnderageStop()
            assertEquals(OnboardingStep.UnderageStop, early.onboarding.state.value.step)

            // An expired stop is cleared and ignored.
            val expired = Fixture(underageStore = InMemoryUnderageStopStore(NOW_MILLIS - OnboardingStateHolder.UNDERAGE_STOP_MILLIS))
            expired.onboarding.resumePendingSignupIfNeeded()
            expired.onboarding.startCreateAccount()
            assertEquals(OnboardingStep.Dob, expired.onboarding.state.value.step)
            assertNull(expired.underageStore.load())
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

            fixture.clockMillis += OnboardingStateHolder.RESEND_COOLDOWN_MILLIS
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
            fixture.clockMillis += OnboardingStateHolder.RESEND_COOLDOWN_MILLIS
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

    // Section 0 repros (onboarding + first grid plan), un-ignored by the Section 1 fixes.

    // Section 0 repro: B1 auth mode not reset after sign-out — fixed in Section 1
    @Test
    fun signingOutAfterASignUpLeavesTheAuthScreenInSignInMode() =
        runTest {
            val fixture = Fixture()
            fixture.completeSignupToGrid()
            // signupWithDetails used to flip the auth mode to a legacy create-account form (now
            // removed); nothing flipped it back after sign-out.
            fixture.auth.logout()

            assertEquals(AuthMode.SignIn, fixture.auth.state.value.mode)
            assertTrue(fixture.auth.state.value.showWelcome)
        }

    // Section 0 repro: B1 auth mode not reset after cancelling a sign-up — fixed in Section 1
    @Test
    fun cancellingASignUpLeavesTheAuthScreenInSignInMode() =
        runTest {
            val fixture = Fixture()
            fixture.startSignup()

            fixture.onboarding.cancelToSignIn()

            assertFalse(fixture.onboarding.state.value.active)
            assertEquals(AuthMode.SignIn, fixture.auth.state.value.mode)
        }

    @Test
    fun signInFromTheStepperOpensTheFormWithTheTypedAddressAndNoStaleError() =
        runTest {
            val fixture =
                Fixture(
                    authApi = FakeAuthApi(verifyResult = { AppResult.Failure(AppError("INVALID_SIGNUP_CODE", "That code isn't right.")) }),
                )
            fixture.startSignup()
            fixture.onboarding.updateSignupCode("000000")
            fixture.onboarding.verifySignupCode()
            // The failed verify left its message in the auth state too.
            assertNotNull(fixture.auth.state.value.errorMessage)

            fixture.onboarding.cancelToSignIn()

            val auth = fixture.auth.state.value
            assertFalse(auth.showWelcome)
            assertEquals("new@pynapli.local", auth.email)
            assertNull(auth.errorMessage)
        }

    @Test
    fun aResumedAddressIsNeverCarriedIntoTheSignInForm() =
        runTest {
            val fixture =
                Fixture(
                    pendingStore = InMemoryPendingSignupStore(PendingSignup("new@pynapli.local", "stored-token", NOW_MILLIS)),
                )
            fixture.onboarding.resumePendingSignupIfNeeded()
            assertTrue(fixture.onboarding.state.value.emailShownMasked)

            fixture.onboarding.cancelToSignIn()

            // Whoever opened the app may not be the person who started: the address stays hidden.
            assertEquals("", fixture.auth.state.value.email)
            assertEquals("", fixture.onboarding.state.value.email)
            assertNull(fixture.pendingStore.load())
        }

    @Test
    fun theNextPersonsSignUpStartsEmptyAfterTheLastOneFinishedAndSignedOut() =
        runTest {
            val fixture = Fixture()
            fixture.startSignup()
            fixture.onboarding.updateSignupCode("123456")
            fixture.onboarding.verifySignupCode()
            fixture.onboarding.continueFromLocation()
            fixture.onboarding.updateDisplayName("Alex")
            fixture.onboarding.finishProfile()

            // Finishing already forgets A's answers...
            assertEquals(OnboardingUiState(), fixture.onboarding.state.value.copy(photoNudgeEligible = false, sessionSettledFor = null))

            fixture.auth.logout()
            fixture.onboarding.resumePendingSignupIfNeeded() // App.kt's signed-out effect
            fixture.onboarding.startCreateAccount()

            // ...and B's Create account starts on an empty birthday, with nothing of A's anywhere.
            val state = fixture.onboarding.state.value
            assertEquals(OnboardingUiState(active = true, step = OnboardingStep.Dob), state)
            assertEquals("", fixture.auth.state.value.email)
        }

    @Test
    fun aNewSignUpNeverReusesAnAbandonedOnesAnswers() =
        runTest {
            val fixture = Fixture()
            fixture.advanceToAccount(day = "15", month = "08", year = "1990")
            fixture.onboarding.updateEmail("someone@pynapli.local")
            fixture.onboarding.cancelToWelcome()

            fixture.onboarding.startCreateAccount()

            val state = fixture.onboarding.state.value
            assertEquals(listOf("", "", ""), listOf(state.dobDay, state.dobMonth, state.dobYear))
            assertNull(state.dob)
            assertEquals("", state.email)
            assertFalse(state.acceptedTerms)
            assertFalse(state.acceptedPrivacy)
        }

    @Test
    fun cancellingFromTheFirstStepReturnsToTheWelcomeLanding() =
        runTest {
            val fixture = Fixture()
            fixture.onboarding.startCreateAccount()

            fixture.onboarding.cancelToWelcome()

            assertFalse(fixture.onboarding.state.value.active)
            assertTrue(fixture.auth.state.value.showWelcome)
            assertEquals(AuthMode.SignIn, fixture.auth.state.value.mode)
        }

    // Section 0 repro: B3 grid shows while a sign-up resume waits for /me — fixed in Section 1
    @Test
    fun aResumedSignUpHoldsTheStepperWhileTheProfileLoads() =
        runTest {
            val meGate = CompletableDeferred<Unit>()
            val fixture =
                Fixture(
                    progressStore = InMemoryProgressStore(OnboardingProgress(completed = false, userId = "user-1")),
                    profileRepository = FakeOnboardingProfileRepository(getMeGate = meGate),
                )
            fixture.signIn("user-1")

            val ready = launch { fixture.onboarding.onSessionReady() }
            runCurrent()

            // App.kt draws the main shell (and composes GridScreen, which starts a grid load) whenever
            // the session is settled, authenticated and the stepper is not active. While /me is in
            // flight that is exactly this state, so the grid shows before the Profile step does.
            assertNotNull(fixture.auth.state.value.session)
            assertTrue(fixture.onboarding.state.value.active, "the stepper must hold the screen while the resume is decided")

            meGate.complete(Unit)
            ready.join()
            assertEquals(OnboardingStep.Profile, fixture.onboarding.state.value.step)
        }

    // Section 0 repro: photo nudge can arm in the sign-up session — fixed in Section 1
    @Test
    fun thePhotoNudgeNeverArmsInTheSignUpSession() =
        runTest {
            val fixture = Fixture()
            fixture.startSignup()
            fixture.onboarding.updateSignupCode("123456")
            // Hold the "onboarding in progress" write, the way a device store slower than a frame does.
            val saveGate = CompletableDeferred<Unit>()
            fixture.progressStore.saveGate = saveGate

            val verifying = launch { fixture.onboarding.verifySignupCode() }
            runCurrent()
            // The session is already live, so App.kt's session effect calls onSessionReady() here,
            // while the stored progress still reads completed = true (its default).
            assertNotNull(fixture.auth.state.value.session)
            fixture.onboarding.onSessionReady()

            saveGate.complete(Unit)
            verifying.join()
            fixture.onboarding.continueFromLocation()
            fixture.onboarding.finishProfile()

            // Spec: the first nudge lands a session later, never in the session the stepper ran.
            assertFalse(fixture.onboarding.state.value.active)
            assertFalse(fixture.onboarding.state.value.photoNudgeEligible)
        }

    @Test
    fun theMainSurfacesWaitUntilTheSessionIsDecided() =
        runTest {
            val fixture = Fixture()
            assertFalse(fixture.onboarding.holdsMainSurfaces(sessionUserId = null), "signed out: nothing to hold")

            fixture.signIn("user-1")
            // Before onSessionReady has run, a resume may still be coming: no grid yet.
            assertTrue(fixture.onboarding.holdsMainSurfaces("user-1"))

            fixture.onboarding.onSessionReady()
            assertFalse(fixture.onboarding.holdsMainSurfaces("user-1"))
            // Another account on this device is decided afresh.
            assertTrue(fixture.onboarding.holdsMainSurfaces("user-2"))

            // Signed out, then back in as the same account: decided afresh too.
            fixture.onboarding.resumePendingSignupIfNeeded()
            assertTrue(fixture.onboarding.holdsMainSurfaces("user-1"))
        }

    @Test
    fun monthLengthsAndLeapYearsFollowTheCalendar() {
        assertEquals(31, OnboardingStateHolder.daysInMonth(2001, 1))
        assertEquals(28, OnboardingStateHolder.daysInMonth(2001, 2))
        assertEquals(29, OnboardingStateHolder.daysInMonth(2000, 2), "divisible by 400: leap")
        assertEquals(28, OnboardingStateHolder.daysInMonth(1900, 2), "divisible by 100, not 400: not leap")
        assertEquals(29, OnboardingStateHolder.daysInMonth(2004, 2))
        assertEquals(30, OnboardingStateHolder.daysInMonth(2001, 4))
        assertEquals(30, OnboardingStateHolder.daysInMonth(2001, 11))
        assertEquals(31, OnboardingStateHolder.daysInMonth(2001, 12))
    }

    @Test
    fun theWheelDayIsClampedToTheMonth() {
        assertEquals(30, OnboardingStateHolder.clampWheelDay(2001, 4, 31))
        assertEquals(28, OnboardingStateHolder.clampWheelDay(2001, 2, 30))
        assertEquals(29, OnboardingStateHolder.clampWheelDay(2000, 2, 31))
        assertEquals(15, OnboardingStateHolder.clampWheelDay(2001, 2, 15))
        assertEquals(1, OnboardingStateHolder.clampWheelDay(2001, 2, 0))
    }

    @Test
    fun theWheelStartsOnANeutralDateThatIsNotAnAnswer() =
        runTest {
            val fixture = Fixture()
            fixture.onboarding.startCreateAccount()

            fixture.onboarding.showBirthdayWheel()

            val state = fixture.onboarding.state.value
            val neutral = OnboardingStateHolder.neutralWheelDate(today)
            assertEquals(LocalDate(today.year - OnboardingStateHolder.NEUTRAL_WHEEL_AGE_YEARS, 1, 1), neutral)
            assertFalse(neutral == LocalDate(today.year - OnboardingStateHolder.MINIMUM_AGE_YEARS, today.month, today.day))
            assertEquals(listOf("01", "01", neutral.year.toString()), listOf(state.dobDay, state.dobMonth, state.dobYear))
            assertEquals(OnboardingStateHolder.wheelYears(today), state.wheelFirstYear..state.wheelLastYear)
            assertTrue(today.year in state.wheelFirstYear..state.wheelLastYear, "an underage year is reachable, so the stop screen is too")
            // Untouched: Next is off and confirming does nothing.
            assertTrue(state.dobWheelUntouched)
            assertFalse(state.dobComplete)
            fixture.onboarding.confirmDob()
            assertEquals(OnboardingStep.Dob, fixture.onboarding.state.value.step)
        }

    @Test
    fun movingAWheelChoosesTheDateAndClampsTheDayWhenTheMonthShrinks() =
        runTest {
            val fixture = Fixture()
            fixture.onboarding.startCreateAccount()
            fixture.onboarding.showBirthdayWheel()

            fixture.onboarding.selectWheelDate(day = 31, month = 1, year = 2000)
            assertTrue(fixture.onboarding.state.value.dobComplete)
            // January 31 -> February: 29 in a leap year...
            fixture.onboarding.selectWheelDate(day = 31, month = 2, year = 2000)
            assertEquals("29", fixture.onboarding.state.value.dobDay)
            // ...and 28 once the year is not one.
            fixture.onboarding.selectWheelDate(day = 29, month = 2, year = 2001)
            assertEquals("28", fixture.onboarding.state.value.dobDay)
            assertEquals("02", fixture.onboarding.state.value.dobMonth)

            fixture.onboarding.confirmDob()
            assertEquals(LocalDate(2001, 2, 28), fixture.onboarding.state.value.dob)
            assertEquals(OnboardingStep.Policies, fixture.onboarding.state.value.step)
        }

    @Test
    fun theWheelKeepsADateAlreadyEntered() =
        runTest {
            val fixture = Fixture()
            fixture.onboarding.startCreateAccount()
            fixture.enterDob(day = "15", month = "08", year = "1990")

            fixture.onboarding.showBirthdayWheel()

            val state = fixture.onboarding.state.value
            assertEquals(listOf("15", "08", "1990"), listOf(state.dobDay, state.dobMonth, state.dobYear))
            assertFalse(state.dobWheelUntouched)
        }

    @Test
    fun typingInsteadClearsAnUntouchedNeutralDateButKeepsAChosenOne() =
        runTest {
            val untouched = Fixture()
            untouched.onboarding.startCreateAccount()
            untouched.onboarding.showBirthdayWheel()
            untouched.onboarding.useTypedBirthday()
            assertEquals(
                listOf("", "", ""),
                untouched.onboarding.state.value
                    .let { listOf(it.dobDay, it.dobMonth, it.dobYear) },
            )

            val chosen = Fixture()
            chosen.onboarding.startCreateAccount()
            chosen.onboarding.showBirthdayWheel()
            chosen.onboarding.selectWheelDate(day = 3, month = 4, year = 1995)
            chosen.onboarding.useTypedBirthday()
            assertEquals(
                listOf("03", "04", "1995"),
                chosen.onboarding.state.value
                    .let { listOf(it.dobDay, it.dobMonth, it.dobYear) },
            )
        }

    @Test
    fun backStepsThroughThePreCodeStepsAndFromTheFirstToTheWelcome() =
        runTest {
            val fixture = Fixture()
            fixture.advanceToAccount()
            assertEquals(OnboardingStep.Account, fixture.onboarding.state.value.step)

            assertTrue(fixture.onboarding.stepBack())
            assertEquals(OnboardingStep.Policies, fixture.onboarding.state.value.step)
            assertTrue(fixture.onboarding.stepBack())
            assertEquals(OnboardingStep.Dob, fixture.onboarding.state.value.step)
            assertTrue(fixture.onboarding.stepBack())

            assertFalse(fixture.onboarding.state.value.active, "back from the birthday leaves the stepper")
            assertTrue(fixture.auth.state.value.showWelcome, "...for the welcome landing, not out of the app")
        }

    @Test
    fun backFromTheUnderageStopLeavesForTheWelcomeNotTheBirthday() =
        runTest {
            val fixture = Fixture()
            fixture.onboarding.startCreateAccount()
            fixture.enterDob(day = "01", month = "01", year = "2020")
            fixture.onboarding.confirmDob()
            assertEquals(OnboardingStep.UnderageStop, fixture.onboarding.state.value.step)

            assertTrue(fixture.onboarding.stepBack())

            assertFalse(fixture.onboarding.state.value.active)
            assertTrue(fixture.auth.state.value.showWelcome)
        }

    @Test
    fun backFromTheCodeStepDropsTheStartedSignUpAndKeepsATypedAddress() =
        runTest {
            val fixture = Fixture()
            fixture.startSignup()

            assertTrue(fixture.onboarding.stepBack())

            val state = fixture.onboarding.state.value
            assertEquals(OnboardingStep.Account, state.step)
            assertEquals("new@pynapli.local", state.email)
            assertNull(fixture.pendingStore.load())
        }

    @Test
    fun backFromAResumedCodeStepNeverRevealsTheMaskedAddress() =
        runTest {
            val fixture =
                Fixture(
                    pendingStore = InMemoryPendingSignupStore(PendingSignup("new@pynapli.local", "stored-token", NOW_MILLIS)),
                )
            fixture.onboarding.resumePendingSignupIfNeeded()

            fixture.onboarding.stepBack()

            assertEquals(OnboardingStep.Account, fixture.onboarding.state.value.step)
            assertEquals("", fixture.onboarding.state.value.email)
        }

    @Test
    fun backIsConsumedAfterTheCodeBecauseTheAccountExists() =
        runTest {
            val fixture = Fixture()
            fixture.startSignup()
            fixture.onboarding.updateSignupCode("123456")
            fixture.onboarding.verifySignupCode()
            assertEquals(OnboardingStep.Location, fixture.onboarding.state.value.step)

            assertFalse(fixture.onboarding.stepBack())
            assertEquals(OnboardingStep.Location, fixture.onboarding.state.value.step)

            fixture.onboarding.continueFromLocation()
            assertFalse(fixture.onboarding.stepBack())
            assertEquals(OnboardingStep.Profile, fixture.onboarding.state.value.step)
            assertTrue(fixture.onboarding.state.value.active)
        }

    @Test
    fun backIsIgnoredWhileARequestIsInFlightAndOutsideTheStepper() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val fixture = Fixture(authApi = FakeAuthApi(signupGate = gate))
            assertFalse(fixture.onboarding.stepBack(), "nothing to step back from")

            fixture.advanceToAccount()
            fixture.onboarding.updateEmail("new@pynapli.local")
            fixture.onboarding.updatePassword("LongEnoughPassword1!")
            val submitting = launch { fixture.onboarding.submitAccount() }
            runCurrent()
            assertTrue(fixture.onboarding.state.value.isSubmitting)

            assertFalse(fixture.onboarding.stepBack())
            assertEquals(OnboardingStep.Account, fixture.onboarding.state.value.step)

            gate.complete(Unit)
            submitting.join()
            assertEquals(OnboardingStep.VerifyEmail, fixture.onboarding.state.value.step)
        }

    @Test
    fun tenCharactersIsEnoughButACommonPasswordIsRefusedBeforeTheServer() =
        runTest {
            val fixture = Fixture()
            fixture.advanceToAccount()
            fixture.onboarding.updateEmail("new@pynapli.local")

            fixture.onboarding.updatePassword("QWERTY12345")
            fixture.onboarding.submitAccount()
            assertEquals(PasswordRules.TOO_COMMON_MESSAGE, fixture.onboarding.state.value.errorMessage)
            assertNull(fixture.authApi.lastSignupRequest)

            fixture.onboarding.updatePassword("ten-chars!")
            fixture.onboarding.submitAccount()
            assertEquals(OnboardingStep.VerifyEmail, fixture.onboarding.state.value.step)
        }

    @Test
    fun theCodeAsksToSubmitOnceWhenItReachesSixDigits() =
        runTest {
            val fixture = Fixture()
            fixture.startSignup()

            assertFalse(fixture.onboarding.updateSignupCode("12345"))
            assertTrue(fixture.onboarding.updateSignupCode("123456"), "the sixth digit completes the code")
            // The same six digits again (a recomposition, an autofill echo) never submit twice.
            assertFalse(fixture.onboarding.updateSignupCode("123456"))
            assertFalse(fixture.onboarding.updateSignupCode("1234567"))
        }

    @Test
    fun aPastedCodeCompletesInOneGoAndAWrongOneCanBeRetyped() =
        runTest {
            val fixture =
                Fixture(
                    authApi = FakeAuthApi(verifyResult = { AppResult.Failure(AppError("INVALID_SIGNUP_CODE", "That code isn't right.")) }),
                )
            fixture.startSignup()

            assertTrue(fixture.onboarding.updateSignupCode("12 34 56"))
            fixture.onboarding.verifySignupCode()
            // The failure clears the field, so the next complete entry submits again.
            assertEquals("", fixture.onboarding.state.value.signupCode)
            assertTrue(fixture.onboarding.updateSignupCode("654321"))
        }

    @Test
    fun theCodeNeverAsksToSubmitWhileAVerifyIsInFlightOrTheGuessesAreSpent() =
        runTest {
            val fixture =
                Fixture(
                    authApi = FakeAuthApi(verifyResult = { AppResult.Failure(AppError("INVALID_SIGNUP_CODE", "That code isn't right.")) }),
                )
            fixture.startSignup()
            repeat(OnboardingStateHolder.CODE_ATTEMPTS_PER_SIGNUP) {
                fixture.onboarding.updateSignupCode("000000")
                fixture.onboarding.verifySignupCode()
            }
            assertTrue(fixture.onboarding.state.value.signupAttemptsSpent)

            assertFalse(fixture.onboarding.updateSignupCode("123456"))
        }

    @Test
    fun aNewCodeWaitsOutTheCooldownTheSameForEveryAddress() =
        runTest {
            val fixture = Fixture()
            fixture.startSignup()
            assertEquals(30, fixture.onboarding.resendCooldownSecondsLeft())

            fixture.onboarding.resendSignupCode()
            assertTrue(fixture.authApi.resendRequests.isEmpty(), "nothing goes out during the cooldown")

            fixture.clockMillis += 12_500
            assertEquals(18, fixture.onboarding.resendCooldownSecondsLeft())
            fixture.clockMillis += 17_500
            assertEquals(0, fixture.onboarding.resendCooldownSecondsLeft())
            fixture.onboarding.resendSignupCode()
            assertEquals(1, fixture.authApi.resendRequests.size)
            // Each new code starts the wait again.
            assertEquals(30, fixture.onboarding.resendCooldownSecondsLeft())
        }

    @Test
    fun aResumedCodeStepCountsTheCooldownFromWhenTheSignUpStarted() =
        runTest {
            val fixture =
                Fixture(
                    pendingStore = InMemoryPendingSignupStore(PendingSignup("new@pynapli.local", "stored-token", NOW_MILLIS - 10_000)),
                )

            fixture.onboarding.resumePendingSignupIfNeeded()

            assertEquals(20, fixture.onboarding.resendCooldownSecondsLeft())
        }

    @Test
    fun aFailingProgressStoreNeverHoldsTheMainSurfacesForever() =
        runTest {
            val fixture = Fixture(progressStore = InMemoryProgressStore().apply { failLoads = true })
            fixture.signIn("user-1")

            runCatching { fixture.onboarding.onSessionReady() }

            assertFalse(fixture.onboarding.holdsMainSurfaces("user-1"))
        }

    @Test
    fun aResumeKeepsANameTypedWhileTheProfileLoads() =
        runTest {
            val meGate = CompletableDeferred<Unit>()
            val fixture =
                Fixture(
                    progressStore = InMemoryProgressStore(OnboardingProgress(completed = false, userId = "user-1")),
                    profileRepository = FakeOnboardingProfileRepository(getMeResult = { AppResult.Success(meDto("Saved")) }, getMeGate = meGate),
                )
            fixture.signIn("user-1")
            val ready = launch { fixture.onboarding.onSessionReady() }
            runCurrent()

            fixture.onboarding.updateDisplayName("Typed")
            meGate.complete(Unit)
            ready.join()

            assertEquals("Typed", fixture.onboarding.state.value.displayName)
        }

    private inner class Fixture(
        val progressStore: InMemoryProgressStore = InMemoryProgressStore(),
        val pendingStore: InMemoryPendingSignupStore = InMemoryPendingSignupStore(),
        val nowMillis: Long = NOW_MILLIS,
        val authApi: FakeAuthApi = FakeAuthApi(),
        val profileRepository: FakeOnboardingProfileRepository = FakeOnboardingProfileRepository(),
        val sessionStore: InMemorySessionStore = InMemorySessionStore(),
        val underageStore: InMemoryUnderageStopStore = InMemoryUnderageStopStore(),
    ) {
        /** The holder's clock; starts at [nowMillis] and tests move it forward (resend cooldown). */
        var clockMillis: Long = nowMillis

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
                nowEpochMillis = { clockMillis },
                pendingSignupStore = pendingStore,
                underageStopStore = underageStore,
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
        suspend fun advanceToAccount(
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

        /** Runs the whole stepper (blank name, no photo) to the grid with a live session. */
        suspend fun completeSignupToGrid() {
            startSignup()
            onboarding.updateSignupCode("123456")
            onboarding.verifySignupCode()
            onboarding.continueFromLocation()
            onboarding.finishProfile()
            assertNotNull(auth.state.value.session)
            assertFalse(onboarding.state.value.active)
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
    // When set, emailSignup waits for it first: lets a test look at the state while the request is in flight.
    private val signupGate: CompletableDeferred<Unit>? = null,
) : AuthApi {
    var lastSignupRequest: EmailSignupRequestDto? = null
        private set
    val verifyRequests = mutableListOf<EmailSignupVerifyRequestDto>()
    val resendRequests = mutableListOf<EmailSignupResendRequestDto>()

    override suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<EmailSignupStartedDto> {
        lastSignupRequest = request
        signupGate?.await()
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
    /** When set, every save waits for it first: models a device store slower than a frame. */
    var saveGate: CompletableDeferred<Unit>? = null

    /** When true, every load throws: models an unreadable device store. */
    var failLoads: Boolean = false

    override suspend fun load(): OnboardingProgress {
        check(!failLoads) { "progress store unreadable" }
        return progress
    }

    override suspend fun save(progress: OnboardingProgress) {
        saveGate?.await()
        this.progress = progress
    }
}

private class FakeOnboardingProfileRepository(
    private val updateDisplayNameResult: (String) -> AppResult<MeDto> = { AppResult.Success(meDto(it)) },
    private val getMeResult: () -> AppResult<MeDto> = { AppResult.Success(meDto(null)) },
    // When set, getMe waits for it first: lets a test look at the state while /me is in flight.
    private val getMeGate: CompletableDeferred<Unit>? = null,
) : OnboardingProfileRepository {
    var lastDisplayName: String? = null
        private set

    override suspend fun updateDisplayName(displayName: String): AppResult<MeDto> {
        lastDisplayName = displayName
        return updateDisplayNameResult(displayName)
    }

    override suspend fun getMe(): AppResult<MeDto> {
        getMeGate?.await()
        return getMeResult()
    }
}
