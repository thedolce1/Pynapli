package com.pynapli.app.auth

import com.pynapli.app.onboarding.OnboardingProfileRepository
import com.pynapli.app.onboarding.OnboardingStateHolder
import com.pynapli.app.onboarding.OnboardingStep
import com.pynapli.app.profilehub.SignInMethodsStateHolder
import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.auth.SessionStore
import com.pynapli.shared.auth.SocialAuthErrorCodes
import com.pynapli.shared.auth.SocialIdentityRepository
import com.pynapli.shared.auth.SocialProvider
import com.pynapli.shared.auth.StoredSession
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.AuthApi
import com.pynapli.shared.network.ProfileApi
import com.pynapli.shared.network.dto.AppealDto
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.AuthUserDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.EmailSignupStartedDto
import com.pynapli.shared.network.dto.LinkSocialIdentityRequestDto
import com.pynapli.shared.network.dto.LinkedIdentitiesDto
import com.pynapli.shared.network.dto.LinkedIdentityDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import com.pynapli.shared.network.dto.SocialAccountAppealRequestDto
import com.pynapli.shared.network.dto.SocialNonceDto
import com.pynapli.shared.network.dto.SocialSignInConfigDto
import com.pynapli.shared.network.dto.SocialSignInRequestDto
import com.pynapli.shared.network.dto.UnlinkedIdentityDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import com.pynapli.shared.settings.InMemoryPendingSignupStore
import com.pynapli.shared.settings.InMemoryUnderageStopStore
import com.pynapli.shared.settings.OnboardingProgress
import com.pynapli.shared.settings.OnboardingProgressStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Continue with Google / Apple (ONBOARDING_AUTH_SPEC): client behaviour against fakes. */
class SocialSignInTest {
    // ---- Buttons follow the server config AND the platform

    @Test
    fun buttonsAreHiddenUntilTheConfigSaysOtherwise() =
        runTest {
            val fixture = Fixture(config = AppResult.Success(SocialSignInConfigDto(google = true, apple = true)))
            assertEquals(emptyList(), fixture.auth.state.value.socialProviders)

            fixture.auth.loadSocialSignInConfig()

            // The platform only runs Google: Apple stays hidden even though the server has it on.
            assertEquals(listOf(SocialProvider.Google), fixture.auth.state.value.socialProviders)
            assertEquals(setOf(SocialProvider.Google), fixture.provider.warmedUp)
        }

    @Test
    fun aServerFlagThatIsOffHidesTheButton() =
        runTest {
            val fixture = Fixture(config = AppResult.Success(SocialSignInConfigDto(google = false, apple = true)))
            fixture.auth.loadSocialSignInConfig()
            assertEquals(emptyList(), fixture.auth.state.value.socialProviders)
            assertTrue(fixture.provider.warmedUp.isEmpty())
        }

    @Test
    fun aFailedOrMissingConfigHidesEverything() =
        runTest {
            val fixture = Fixture(config = AppResult.Failure(AppError(SocialAuthErrorCodes.UNAVAILABLE, "Not found.")))
            fixture.auth.loadSocialSignInConfig()
            assertEquals(emptyList(), fixture.auth.state.value.socialProviders)
        }

    @Test
    fun aPlatformWithNoProviderNeverAsksTheServer() =
        runTest {
            val fixture = Fixture(provider = FakeSocialProvider(supported = emptySet()))
            fixture.auth.loadSocialSignInConfig()
            assertEquals(0, fixture.api.configCalls)
            assertEquals(emptyList(), fixture.auth.state.value.socialProviders)
        }

    // ---- Sign-in

    @Test
    fun aKnownIdentitySignsInWithAFreshNonceAndNoSignupFields() =
        runTest {
            val fixture = Fixture()
            fixture.auth.loadSocialSignInConfig()

            fixture.onboarding.continueWithSocial(SocialProvider.Google)

            assertEquals("user-1", fixture.auth.state.value.session?.userId)
            assertEquals(listOf("nonce-1"), fixture.provider.noncesSeen)
            val request = fixture.api.signIns.single()
            assertEquals("google", request.first)
            assertEquals("nonce-1", request.second.nonce)
            assertEquals("token-1", request.second.idToken)
            assertNull(request.second.birthdate)
            assertFalse(request.second.acceptedTerms)
            assertFalse(fixture.onboarding.state.value.active)
            assertFalse(fixture.auth.state.value.isSubmitting)
        }

    @Test
    fun everyAttemptFetchesItsOwnNonce() =
        runTest {
            val fixture = Fixture(provider = FakeSocialProvider(results = ArrayDeque(listOf(SocialCredentialResult.Cancelled))))
            fixture.auth.loadSocialSignInConfig()

            fixture.auth.continueWithSocial(SocialProvider.Google)
            fixture.auth.continueWithSocial(SocialProvider.Google)

            assertEquals(2, fixture.api.nonceCalls)
            assertEquals(listOf("nonce-1", "nonce-2"), fixture.provider.noncesSeen)
            assertEquals("nonce-2", fixture.api.signIns.single().second.nonce)
        }

    @Test
    fun anUnknownIdentityRunsBirthdayAndPoliciesThenResendsWithAFreshNonceAndToken() =
        runTest {
            val fixture = Fixture(signInResults = ArrayDeque(listOf(needsAgeGate(), session())))
            fixture.auth.loadSocialSignInConfig()

            fixture.onboarding.continueWithSocial(SocialProvider.Google)
            val stepper = fixture.onboarding.state.value
            assertTrue(stepper.active)
            assertEquals(OnboardingStep.Dob, stepper.step)
            assertEquals(SocialProvider.Google, stepper.socialProvider)
            assertNull(fixture.auth.state.value.session)

            fixture.enterDob("01", "06", "2000")
            fixture.onboarding.confirmDob()
            assertEquals(OnboardingStep.Policies, fixture.onboarding.state.value.step)

            fixture.onboarding.acceptPoliciesAndContinue()

            val second = fixture.api.signIns[1].second
            assertEquals("nonce-2", second.nonce)
            assertEquals("token-2", second.idToken)
            assertEquals("2000-06-01", second.birthdate)
            assertTrue(second.confirmedAdult)
            assertTrue(second.acceptedTerms)
            assertTrue(second.acceptedPrivacy)
            // No email, password or code: straight on to Location with a live session.
            assertEquals(OnboardingStep.Location, fixture.onboarding.state.value.step)
            assertEquals("user-1", fixture.auth.state.value.session?.userId)
            assertEquals(OnboardingProgress(completed = false, userId = "user-1"), fixture.progress.progress)
            assertEquals(0, fixture.api.emailSignups)
        }

    @Test
    fun anUnderageBirthdayStopsTheSocialSignUpAndTheStopSticks() =
        runTest {
            val fixture = Fixture(signInResults = ArrayDeque(listOf(needsAgeGate(), needsAgeGate())))
            fixture.auth.loadSocialSignInConfig()
            fixture.onboarding.continueWithSocial(SocialProvider.Google)

            fixture.enterDob("01", "06", "2015")
            fixture.onboarding.confirmDob()

            assertEquals(OnboardingStep.UnderageStop, fixture.onboarding.state.value.step)
            assertNull(fixture.onboarding.state.value.socialProvider)
            assertEquals(1, fixture.api.signIns.size)

            // Leaving and trying the provider again lands on the same stop, never the birthday.
            fixture.onboarding.cancelToWelcome()
            fixture.onboarding.continueWithSocial(SocialProvider.Google)
            assertEquals(OnboardingStep.UnderageStop, fixture.onboarding.state.value.step)
            assertNull(fixture.onboarding.state.value.socialProvider)
        }

    @Test
    fun anAddressWithAnAccountOpensSignInWithTheNeutralNotice() =
        runTest {
            val fixture = Fixture(signInResults = ArrayDeque(listOf(emailHasAccount())))
            fixture.auth.loadSocialSignInConfig()

            fixture.onboarding.continueWithSocial(SocialProvider.Google)

            val auth = fixture.auth.state.value
            assertEquals(AuthMode.SignIn, auth.mode)
            assertFalse(auth.showWelcome)
            assertEquals(
                "An account already uses this email. Sign in the way you usually do, then add Google in Settings.",
                auth.errorMessage,
            )
            assertNull(auth.session)
            assertFalse(fixture.onboarding.state.value.active)
        }

    @Test
    fun anAddressWithAnAccountFoundAtPoliciesLeavesTheStepperForSignIn() =
        runTest {
            val fixture = Fixture(signInResults = ArrayDeque(listOf(needsAgeGate(), emailHasAccount())))
            fixture.auth.loadSocialSignInConfig()
            fixture.onboarding.continueWithSocial(SocialProvider.Google)
            fixture.enterDob("01", "06", "2000")
            fixture.onboarding.confirmDob()

            fixture.onboarding.acceptPoliciesAndContinue()

            assertFalse(fixture.onboarding.state.value.active)
            assertNull(fixture.onboarding.state.value.socialProvider)
            assertFalse(fixture.auth.state.value.showWelcome)
            assertEquals(AuthStateHolder.emailHasAccountMessage(SocialProvider.Google), fixture.auth.state.value.errorMessage)
        }

    @Test
    fun cancellingTheProviderIsQuiet() =
        runTest {
            val fixture = Fixture(provider = FakeSocialProvider(results = ArrayDeque(listOf(SocialCredentialResult.Cancelled))))
            fixture.auth.loadSocialSignInConfig()

            fixture.onboarding.continueWithSocial(SocialProvider.Google)

            assertTrue(fixture.api.signIns.isEmpty())
            assertNull(fixture.auth.state.value.errorMessage)
            assertFalse(fixture.auth.state.value.isSubmitting)
            assertNull(fixture.auth.state.value.socialInProgress)
            assertEquals(listOf(SocialProvider.Google), fixture.auth.state.value.socialProviders)
        }

    @Test
    fun cancellingAtPoliciesKeepsTheStepOpenForAnotherTry() =
        runTest {
            val provider =
                FakeSocialProvider(
                    results = ArrayDeque(listOf(SocialCredentialResult.Success("token-1"), SocialCredentialResult.Cancelled)),
                )
            val fixture = Fixture(provider = provider, signInResults = ArrayDeque(listOf(needsAgeGate(), session())))
            fixture.auth.loadSocialSignInConfig()
            fixture.onboarding.continueWithSocial(SocialProvider.Google)
            fixture.enterDob("01", "06", "2000")
            fixture.onboarding.confirmDob()

            fixture.onboarding.acceptPoliciesAndContinue()
            assertEquals(OnboardingStep.Policies, fixture.onboarding.state.value.step)
            assertFalse(fixture.onboarding.state.value.isSubmitting)
            assertNull(fixture.onboarding.state.value.errorMessage)

            fixture.onboarding.acceptPoliciesAndContinue()
            assertEquals(OnboardingStep.Location, fixture.onboarding.state.value.step)
            assertEquals(listOf("nonce-1", "nonce-2", "nonce-3"), provider.noncesSeen)
        }

    @Test
    fun aProviderThatCannotRunHereLosesItsButton() =
        runTest {
            val fixture = Fixture(provider = FakeSocialProvider(results = ArrayDeque(listOf(SocialCredentialResult.Unavailable))))
            fixture.auth.loadSocialSignInConfig()

            fixture.auth.continueWithSocial(SocialProvider.Google)

            assertEquals(emptyList(), fixture.auth.state.value.socialProviders)
        }

    @Test
    fun aRejectedTokenAndASwitchedOffServerAreWorded() =
        runTest {
            val fixture =
                Fixture(
                    signInResults =
                        ArrayDeque(
                            listOf(
                                AppResult.Failure(AppError(SocialAuthErrorCodes.INVALID_TOKEN, "raw")),
                                AppResult.Failure(AppError(SocialAuthErrorCodes.UNAVAILABLE, "raw")),
                            ),
                        ),
                )
            fixture.auth.loadSocialSignInConfig()

            fixture.auth.continueWithSocial(SocialProvider.Google)
            assertEquals(AuthStateHolder.SOCIAL_NOT_CONFIRMED, fixture.auth.state.value.errorMessage)

            fixture.auth.continueWithSocial(SocialProvider.Google)
            assertEquals(emptyList(), fixture.auth.state.value.socialProviders)
        }

    @Test
    fun aSuspendedSocialAccountShowsTheMessageAndTheProviderAppealForm() =
        runTest {
            val fixture = Fixture(signInResults = ArrayDeque(listOf(AppResult.Failure(AppError("ACCOUNT_NOT_ACTIVE", "This account is suspended.")))))
            fixture.auth.loadSocialSignInConfig()
            fixture.auth.continueWithSocial(SocialProvider.Google)
            assertEquals("This account is suspended.", fixture.auth.state.value.errorMessage)
            assertTrue(fixture.auth.state.value.accountSuspended)
            assertEquals(SocialProvider.Google, fixture.auth.state.value.socialAppealProvider)
        }

    @Test
    fun aScopeCancelledWhileTheProviderIsUpNeverLeavesTheButtonsBusy() =
        runTest {
            val gate = CompletableDeferred<SocialCredentialResult>()
            val fixture = Fixture(provider = FakeSocialProvider(gate = gate))
            fixture.auth.loadSocialSignInConfig()

            val job = launch { fixture.auth.continueWithSocial(SocialProvider.Google) }
            runCurrent()
            assertTrue(fixture.auth.state.value.isSubmitting)
            assertEquals(SocialProvider.Google, fixture.auth.state.value.socialInProgress)

            job.cancel()
            runCurrent()
            assertFalse(fixture.auth.state.value.isSubmitting)
            assertNull(fixture.auth.state.value.socialInProgress)
            assertNull(fixture.auth.state.value.errorMessage)
        }

    @Test
    fun createAccountDuringASocialAttemptIsIgnored() =
        runTest {
            val gate = CompletableDeferred<SocialCredentialResult>()
            val fixture = Fixture(provider = FakeSocialProvider(gate = gate))
            fixture.auth.loadSocialSignInConfig()

            val job = launch { fixture.onboarding.continueWithSocial(SocialProvider.Google) }
            runCurrent()
            fixture.onboarding.startCreateAccount()
            assertFalse(fixture.onboarding.state.value.active)

            gate.complete(SocialCredentialResult.Success("token-1"))
            job.join()
            assertEquals("user-1", fixture.auth.state.value.session?.userId)
            assertFalse(fixture.onboarding.state.value.active)
        }

    // ---- Trademark guards: no official artwork, no button

    @Test
    fun aProviderWithoutItsOfficialArtworkGetsNoButton() {
        val both = listOf(SocialProvider.Google, SocialProvider.Apple)
        assertEquals(emptyList(), visibleSocialProviders(both, SocialMarkArtwork(googleOfficial = false, appleOfficial = false)))
        assertEquals(listOf(SocialProvider.Google), visibleSocialProviders(both, SocialMarkArtwork(googleOfficial = true, appleOfficial = false)))
        assertEquals(listOf(SocialProvider.Apple), visibleSocialProviders(both, SocialMarkArtwork(googleOfficial = false, appleOfficial = true)))
        assertEquals(both, visibleSocialProviders(both, SocialMarkArtwork(googleOfficial = true, appleOfficial = true)))
    }

    @Test
    fun theShippedGuardsHideEveryButtonUntilTheFilesAreIn() {
        // Both official files are absent from the repository, so both guards ship false.
        assertEquals(emptyList(), visibleSocialProviders(listOf(SocialProvider.Google, SocialProvider.Apple)))
    }

    @Test
    fun settingsOffersNoAddWithoutTheArtworkButStillListsALinkedProvider() =
        runTest {
            val noArtwork = SocialMarkArtwork(googleOfficial = false, appleOfficial = false)
            val withArtwork = SocialMarkArtwork(googleOfficial = true, appleOfficial = false)

            val unlinked = SignInMethodsStateHolder(SocialIdentityRepository(FakeSocialAuthApi()), FakeSocialProvider())
            unlinked.load()
            assertEquals(emptyList(), unlinked.state.value.offered(noArtwork))
            assertFalse(unlinked.state.value.visibleWith(noArtwork))
            assertEquals(listOf(SocialProvider.Google), unlinked.state.value.offered(withArtwork))
            assertTrue(unlinked.state.value.visibleWith(withArtwork))

            val linkedApi = FakeSocialAuthApi(methods = identitiesOf("google"))
            val linked = SignInMethodsStateHolder(SocialIdentityRepository(linkedApi), FakeSocialProvider())
            linked.load()
            assertTrue(linked.state.value.visibleWith(noArtwork))
            assertEquals(emptyList(), linked.state.value.offered(noArtwork))
        }

    // ---- Change password only for accounts known to have one

    @Test
    fun changePasswordShowsOnlyWhenTheServerSaysThereIsAPassword() =
        runTest {
            suspend fun shown(api: FakeSocialAuthApi, provider: FakeSocialProvider = FakeSocialProvider()): Boolean {
                val holder = SignInMethodsStateHolder(SocialIdentityRepository(api), provider)
                holder.load()
                return holder.state.value.showChangePassword
            }
            assertTrue(shown(FakeSocialAuthApi(methods = identitiesOf(hasPassword = true))))
            assertFalse(shown(FakeSocialAuthApi(methods = identitiesOf("google", hasPassword = false))))
            // The call failed: unknown, quietly.
            assertFalse(shown(FakeSocialAuthApi(methodsResult = AppResult.Failure(AppError("HTTP_404", "Not found.")))))
            // An email account on a platform with no provider still learns it has a password.
            assertTrue(shown(FakeSocialAuthApi(methods = identitiesOf(hasPassword = true)), FakeSocialProvider(supported = emptySet())))
        }

    @Test
    fun aFailedLoadIsQuietAndSignOutForgetsThePasswordFlag() =
        runTest {
            val failing = SignInMethodsStateHolder(SocialIdentityRepository(FakeSocialAuthApi(methodsResult = AppResult.Failure(AppError("HTTP_500", "Boom.")))), FakeSocialProvider())
            failing.load()
            assertNull(failing.state.value.errorMessage)
            assertNull(failing.state.value.hasPassword)

            val holder = SignInMethodsStateHolder(SocialIdentityRepository(FakeSocialAuthApi(methods = identitiesOf(hasPassword = true))), FakeSocialProvider())
            holder.load()
            assertTrue(holder.state.value.showChangePassword)
            holder.reset()
            assertFalse(holder.state.value.showChangePassword)
        }

    // ---- Suspended account signing in with a provider: appeal through a fresh provider sign-in

    @Test
    fun aSuspendedSocialAccountAppealsWithAFreshNonceAndProviderSignIn() =
        runTest {
            val fixture = Fixture(signInResults = ArrayDeque(listOf(AppResult.Failure(AppError("ACCOUNT_NOT_ACTIVE", "This account is suspended.")))))
            fixture.auth.loadSocialSignInConfig()
            fixture.onboarding.continueWithSocial(SocialProvider.Google)

            val suspended = fixture.auth.state.value
            assertTrue(suspended.accountSuspended)
            assertEquals(SocialProvider.Google, suspended.socialAppealProvider)
            assertFalse(suspended.showWelcome)
            assertEquals("This account is suspended.", suspended.errorMessage)

            fixture.auth.updateAppealReason("I was reported by someone I had blocked.")
            fixture.auth.submitAccountAppeal()

            val (path, appeal) = fixture.api.socialAppeals.single()
            assertEquals("google", path)
            // A new nonce (the sign-in spent the first), raw to Pynapli, and a new provider token.
            assertEquals("nonce-2", appeal.nonce)
            assertEquals("token-2", appeal.idToken)
            assertEquals("I was reported by someone I had blocked.", appeal.reason)
            assertTrue(fixture.auth.state.value.appealSent)
            assertFalse(fixture.auth.state.value.isSubmitting)
            assertNull(fixture.auth.state.value.session)
        }

    @Test
    fun socialAppealRefusalsAreWorded() =
        runTest {
            val fixture = Fixture(signInResults = ArrayDeque(listOf(AppResult.Failure(AppError("ACCOUNT_NOT_ACTIVE", "Suspended.")))))
            val api = fixture.api
            fixture.auth.loadSocialSignInConfig()
            fixture.auth.continueWithSocial(SocialProvider.Google)
            fixture.auth.updateAppealReason("Please take another look at this.")
            api.nextAppealResult = AppResult.Failure(AppError(SocialAuthErrorCodes.ACCOUNT_NOT_RESTRICTED, "raw"))

            fixture.auth.submitAccountAppeal()

            assertEquals("This account isn't restricted any more. Try signing in again.", fixture.auth.state.value.errorMessage)
            assertFalse(fixture.auth.state.value.appealSent)
        }

    @Test
    fun typingAnEmailSwitchesTheAppealBackToTheEmailForm() =
        runTest {
            val fixture = Fixture(signInResults = ArrayDeque(listOf(AppResult.Failure(AppError("ACCOUNT_NOT_ACTIVE", "Suspended.")))))
            fixture.auth.loadSocialSignInConfig()
            fixture.auth.continueWithSocial(SocialProvider.Google)
            fixture.auth.updateEmail("kai@pynapli.local")
            assertNull(fixture.auth.state.value.socialAppealProvider)
            assertFalse(fixture.auth.state.value.accountSuspended)
        }

    @Test
    fun linkingWhenAnotherIdentityOfThatProviderIsLinkedIsWorded() =
        runTest {
            val api = FakeSocialAuthApi(linkResult = AppResult.Failure(AppError(SocialAuthErrorCodes.PROVIDER_ALREADY_LINKED, "raw")))
            val holder = SignInMethodsStateHolder(SocialIdentityRepository(api), FakeSocialProvider())
            holder.load()
            holder.link(SocialProvider.Google)
            assertEquals(
                "This account already has a Google account added. Remove it first to add a different one.",
                holder.state.value.errorMessage,
            )
        }

    @Test
    fun unlinkingSomethingAlreadyGoneJustRefreshes() =
        runTest {
            val api =
                FakeSocialAuthApi(
                    methods = identitiesOf("google"),
                    unlinkResult = AppResult.Failure(AppError(SocialAuthErrorCodes.IDENTITY_NOT_LINKED, "raw")),
                )
            val holder = SignInMethodsStateHolder(SocialIdentityRepository(api), FakeSocialProvider())
            holder.load()
            api.methods = identitiesOf()

            holder.unlink(SocialProvider.Google)

            assertNull(holder.state.value.errorMessage)
            assertFalse(holder.state.value.isLinked(SocialProvider.Google))
        }

    // ---- Back, cancel and sign-out

    @Test
    fun backStepsThroughTheSocialSignUpAndLeavingForgetsTheProvider() =
        runTest {
            val fixture = Fixture(signInResults = ArrayDeque(listOf(needsAgeGate())))
            fixture.auth.loadSocialSignInConfig()
            fixture.onboarding.continueWithSocial(SocialProvider.Google)
            fixture.enterDob("01", "06", "2000")
            fixture.onboarding.confirmDob()

            assertTrue(fixture.onboarding.stepBack())
            assertEquals(OnboardingStep.Dob, fixture.onboarding.state.value.step)
            assertEquals(SocialProvider.Google, fixture.onboarding.state.value.socialProvider)

            assertTrue(fixture.onboarding.stepBack())
            assertFalse(fixture.onboarding.state.value.active)
            assertNull(fixture.onboarding.state.value.socialProvider)
            assertTrue(fixture.auth.state.value.showWelcome)

            // Create account afterwards is a plain email sign-up: Accept goes to the account step.
            fixture.onboarding.startCreateAccount()
            assertNull(fixture.onboarding.state.value.socialProvider)
            fixture.enterDob("01", "06", "2000")
            fixture.onboarding.confirmDob()
            fixture.onboarding.acceptPoliciesAndContinue()
            assertEquals(OnboardingStep.Account, fixture.onboarding.state.value.step)
            assertEquals(1, fixture.api.signIns.size)
        }

    @Test
    fun signOutAfterASocialSignUpResetsTheProviderAndTheStepper() =
        runTest {
            val fixture = Fixture(signInResults = ArrayDeque(listOf(needsAgeGate(), session())))
            fixture.auth.loadSocialSignInConfig()
            fixture.onboarding.continueWithSocial(SocialProvider.Google)
            fixture.enterDob("01", "06", "2000")
            fixture.onboarding.confirmDob()
            fixture.onboarding.acceptPoliciesAndContinue()
            fixture.onboarding.continueFromLocation()
            fixture.onboarding.finishProfile()

            fixture.auth.logout()
            fixture.onboarding.resumePendingSignupIfNeeded()

            assertNull(fixture.auth.state.value.session)
            assertTrue(fixture.auth.state.value.showWelcome)
            assertFalse(fixture.onboarding.state.value.active)
            assertNull(fixture.onboarding.state.value.socialProvider)
            assertNull(fixture.onboarding.state.value.dob)
            assertEquals(1, fixture.provider.signedOutCalls)
        }

    // ---- Settings > Sign-in methods

    @Test
    fun signInMethodsShowOnlyWhenAProviderIsOfferedAndListTheAccount() =
        runTest {
            val api =
                FakeSocialAuthApi(
                    methods =
                        LinkedIdentitiesDto(
                            identities =
                                listOf(
                                    LinkedIdentityDto(provider = "email", linkedAt = LINKED_AT),
                                    LinkedIdentityDto(provider = "google", linkedAt = LINKED_AT, emailHint = "a•••@gmail.com"),
                                ),
                            hasPassword = true,
                        ),
                )
            val holder = SignInMethodsStateHolder(SocialIdentityRepository(api), FakeSocialProvider())
            holder.load()
            val state = holder.state.value
            assertTrue(state.visible)
            assertTrue(state.isLinked(SocialProvider.Google))
            // The password method ("email") is the Password line, never a provider row.
            assertEquals(listOf(SocialProvider.Google), state.linked.map { it.provider })
            assertEquals("a•••@gmail.com", state.linked.single().emailHint)
            assertEquals("Password, Google", state.summary)

            val off = SignInMethodsStateHolder(SocialIdentityRepository(FakeSocialAuthApi()), FakeSocialProvider(supported = emptySet()))
            off.load()
            assertFalse(off.state.value.visible)
        }

    @Test
    fun linkingUsesAFreshNonceAndRefetches() =
        runTest {
            val api = FakeSocialAuthApi()
            val provider = FakeSocialProvider()
            val holder = SignInMethodsStateHolder(SocialIdentityRepository(api), provider)
            holder.load()

            holder.link(SocialProvider.Google)
            holder.link(SocialProvider.Google)

            assertEquals(listOf("nonce-1", "nonce-2"), api.links.map { it.second.nonce })
            assertEquals(listOf("nonce-1", "nonce-2"), provider.noncesSeen)
            // The link answer is the updated list: only the first load fetches it.
            assertEquals(1, api.methodsCalls)
            assertNull(holder.state.value.busyProvider)
        }

    @Test
    fun linkingAGoogleAccountThatBelongsToSomeoneElseIsWorded() =
        runTest {
            val api = FakeSocialAuthApi(linkResult = AppResult.Failure(AppError(SocialAuthErrorCodes.IDENTITY_IN_USE, "raw")))
            val holder = SignInMethodsStateHolder(SocialIdentityRepository(api), FakeSocialProvider())
            holder.load()
            holder.link(SocialProvider.Google)
            assertEquals(
                "That Google account is already used to sign in to another Pynapli account.",
                holder.state.value.errorMessage,
            )
        }

    @Test
    fun removingTheLastSignInMethodIsRefusedAndExplained() =
        runTest {
            val api =
                FakeSocialAuthApi(
                    methods = identitiesOf("google", hasPassword = false),
                    unlinkResult = AppResult.Failure(AppError(SocialAuthErrorCodes.LAST_SIGN_IN_METHOD, "raw")),
                )
            val holder = SignInMethodsStateHolder(SocialIdentityRepository(api), FakeSocialProvider())
            holder.load()

            holder.unlink(SocialProvider.Google)

            assertEquals(SignInMethodsStateHolder.LAST_METHOD_MESSAGE, holder.state.value.errorMessage)
            assertTrue(holder.state.value.isLinked(SocialProvider.Google))
            assertEquals(listOf("google"), api.unlinks)
        }

    @Test
    fun unlinkingRefetchesAndSignOutResets() =
        runTest {
            val api = FakeSocialAuthApi(methods = identitiesOf("google"))
            val holder = SignInMethodsStateHolder(SocialIdentityRepository(api), FakeSocialProvider())
            holder.load()
            api.methods = identitiesOf()

            holder.unlink(SocialProvider.Google)
            assertFalse(holder.state.value.isLinked(SocialProvider.Google))
            assertEquals("Google removed.", holder.state.value.feedbackMessage)

            holder.reset()
            assertFalse(holder.state.value.visible)
            assertTrue(holder.state.value.linked.isEmpty())
        }

    // ---- Fixture

    private class Fixture(
        config: AppResult<SocialSignInConfigDto> = AppResult.Success(SocialSignInConfigDto(google = true, apple = false)),
        signInResults: ArrayDeque<AppResult<AuthSessionDto>> = ArrayDeque(listOf(session())),
        val provider: FakeSocialProvider = FakeSocialProvider(),
    ) {
        val api = FakeSocialAuthApi(config = config, signInResults = signInResults)
        val sessionStore = MemorySessionStore()
        val progress = MemoryProgressStore()
        val auth =
            AuthStateHolder(
                authRepository = AuthRepository(authApi = api, profileApi = NoProfileApi(), sessionStore = sessionStore),
                platform = "test",
                appVersion = "0.0.0",
                socialSignInProvider = provider,
            )
        val onboarding =
            OnboardingStateHolder(
                authStateHolder = auth,
                profileRepository = NoOnboardingProfileRepository(),
                progressStore = progress,
                pendingSignupStore = InMemoryPendingSignupStore(),
                todayProvider = { LocalDate(2026, 11, 26) },
                nowEpochMillis = { 1_781_000_000_000L },
                underageStopStore = InMemoryUnderageStopStore(),
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
    }
}

private fun session(userId: String = "user-1"): AppResult<AuthSessionDto> =
    AppResult.Success(AuthSessionDto(accessToken = "access", refreshToken = "refresh", user = AuthUserDto(userId = userId, status = "active")))

private fun needsAgeGate(): AppResult<AuthSessionDto> = AppResult.Failure(AppError(SocialAuthErrorCodes.NEEDS_AGE_GATE, "Tell us your birthday."))

private fun emailHasAccount(): AppResult<AuthSessionDto> = AppResult.Failure(AppError(SocialAuthErrorCodes.EMAIL_HAS_ACCOUNT, "raw server words"))

private val unimplemented = AppError("UNIMPLEMENTED", "Not implemented.")

private const val LINKED_AT = "2026-11-26T00:00:00Z"

/** A `GET /v1/auth/identities` answer with the password method (when [hasPassword]) and [providers]. */
private fun identitiesOf(
    vararg providers: String,
    hasPassword: Boolean = true,
): LinkedIdentitiesDto =
    LinkedIdentitiesDto(
        identities =
            (if (hasPassword) listOf(LinkedIdentityDto(provider = "email", linkedAt = LINKED_AT)) else emptyList()) +
                providers.map { LinkedIdentityDto(provider = it, linkedAt = LINKED_AT) },
        hasPassword = hasPassword,
    )

/** Supports [supported]; hands out token-1, token-2, ... unless [results] or [gate] say otherwise. */
private class FakeSocialProvider(
    private val supported: Set<SocialProvider> = setOf(SocialProvider.Google),
    private val results: ArrayDeque<SocialCredentialResult> = ArrayDeque(),
    private val gate: CompletableDeferred<SocialCredentialResult>? = null,
) : SocialSignInProvider {
    val noncesSeen = mutableListOf<String>()
    var warmedUp: Set<SocialProvider> = emptySet()
        private set
    var signedOutCalls = 0
        private set

    override fun supports(provider: SocialProvider): Boolean = provider in supported

    override fun warmUp(providers: Set<SocialProvider>) {
        warmedUp = providers
    }

    override suspend fun requestIdToken(
        provider: SocialProvider,
        nonce: String,
    ): SocialCredentialResult {
        noncesSeen += nonce
        gate?.let { return it.await() }
        return results.removeFirstOrNull() ?: SocialCredentialResult.Success(idToken = "token-${noncesSeen.size}")
    }

    override suspend fun onSignedOut() {
        signedOutCalls++
    }
}

private class FakeSocialAuthApi(
    private val config: AppResult<SocialSignInConfigDto> = AppResult.Success(SocialSignInConfigDto(google = true, apple = false)),
    private val signInResults: ArrayDeque<AppResult<AuthSessionDto>> = ArrayDeque(),
    var methods: LinkedIdentitiesDto = identitiesOf(),
    private val methodsResult: AppResult<LinkedIdentitiesDto>? = null,
    private val linkResult: AppResult<LinkedIdentitiesDto>? = null,
    private val unlinkResult: AppResult<UnlinkedIdentityDto>? = null,
    private val appealResult: AppResult<AppealDto>? = null,
) : AuthApi {
    var configCalls = 0
        private set
    var nonceCalls = 0
        private set
    var methodsCalls = 0
        private set
    var emailSignups = 0
        private set
    val signIns = mutableListOf<Pair<String, SocialSignInRequestDto>>()
    val links = mutableListOf<Pair<String, LinkSocialIdentityRequestDto>>()
    val unlinks = mutableListOf<String>()
    val socialAppeals = mutableListOf<Pair<String, SocialAccountAppealRequestDto>>()
    var nextAppealResult: AppResult<AppealDto>? = null

    override suspend fun socialSignInConfig(): AppResult<SocialSignInConfigDto> {
        configCalls++
        return config
    }

    override suspend fun socialNonce(deviceId: String?): AppResult<SocialNonceDto> {
        nonceCalls++
        return AppResult.Success(SocialNonceDto(nonce = "nonce-$nonceCalls", expiresInSeconds = 300))
    }

    override suspend fun socialSignIn(
        providerWireName: String,
        request: SocialSignInRequestDto,
    ): AppResult<AuthSessionDto> {
        signIns += providerWireName to request
        return signInResults.removeFirstOrNull() ?: session()
    }

    override suspend fun submitSocialAccountAppeal(
        providerWireName: String,
        request: SocialAccountAppealRequestDto,
    ): AppResult<AppealDto> {
        socialAppeals += providerWireName to request
        return nextAppealResult ?: appealResult
            ?: AppResult.Success(AppealDto("appeal-1", "account_suspension", "account", status = "open", userReason = request.reason, createdAt = "", updatedAt = ""))
    }

    override suspend fun listSignInMethods(): AppResult<LinkedIdentitiesDto> {
        methodsCalls++
        return methodsResult ?: AppResult.Success(methods)
    }

    override suspend fun linkSocialIdentity(
        providerWireName: String,
        request: LinkSocialIdentityRequestDto,
    ): AppResult<LinkedIdentitiesDto> {
        links += providerWireName to request
        return linkResult ?: AppResult.Success(identitiesOf(providerWireName, hasPassword = methods.hasPassword))
    }

    override suspend fun unlinkSocialIdentity(providerWireName: String): AppResult<UnlinkedIdentityDto> {
        unlinks += providerWireName
        return unlinkResult ?: AppResult.Success(UnlinkedIdentityDto(unlinked = true))
    }

    override suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<EmailSignupStartedDto> {
        emailSignups++
        return AppResult.Failure(unimplemented)
    }

    override suspend fun emailLogin(request: EmailLoginRequestDto): AppResult<AuthSessionDto> = AppResult.Failure(unimplemented)

    override suspend fun refreshSession(request: RefreshSessionRequestDto): AppResult<AuthSessionDto> = AppResult.Failure(unimplemented)

    override suspend fun logout(): AppResult<LogoutResponseDto> = AppResult.Success(LogoutResponseDto(loggedOut = true))
}

private class NoProfileApi : ProfileApi {
    override suspend fun getMe(): AppResult<MeDto> = AppResult.Failure(unimplemented)

    override suspend fun updateProfile(request: UpdateProfileRequestDto): AppResult<MeDto> = AppResult.Failure(unimplemented)

    override suspend fun updateDiscoverySettings(request: UpdateDiscoverySettingsRequestDto): AppResult<MeDto> = AppResult.Failure(unimplemented)

    override suspend fun getPublicProfile(userId: String): AppResult<PublicProfileDto> = AppResult.Failure(unimplemented)
}

private class NoOnboardingProfileRepository : OnboardingProfileRepository {
    override suspend fun updateDisplayName(displayName: String): AppResult<MeDto> = AppResult.Failure(unimplemented)

    override suspend fun getMe(): AppResult<MeDto> = AppResult.Failure(unimplemented)
}

private class MemorySessionStore : SessionStore {
    private var session: StoredSession? = null

    override suspend fun currentSession(): StoredSession? = session

    override suspend fun save(session: AuthSessionDto) {
        this.session = StoredSession(session.accessToken, session.refreshToken, session.user.userId, session.user.status)
    }

    override suspend fun clear() {
        session = null
    }
}

private class MemoryProgressStore : OnboardingProgressStore {
    var progress = OnboardingProgress()

    override suspend fun load(): OnboardingProgress = progress

    override suspend fun save(progress: OnboardingProgress) {
        this.progress = progress
    }
}
