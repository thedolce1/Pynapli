package com.pynapli.server.auth.social

import com.pynapli.server.account.AccountLifecycleService
import com.pynapli.server.account.JdbcAccountDataExportArchiver
import com.pynapli.server.account.JdbcAccountLifecycleRepository
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.EmailSignupService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.JdbcEmailSignupRepository
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.contactblocks.ContactBlockTokens
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.safety.AppealService
import com.pynapli.server.safety.JdbcAppealRepository
import com.pynapli.server.safety.configureAppealRoutes
import com.pynapli.server.testsupport.CapturingSignupMailer
import com.pynapli.server.testsupport.FakeIdentityProvider
import com.pynapli.server.testsupport.FakeIdentityProvider.Companion.bool
import com.pynapli.server.testsupport.FakeIdentityProvider.Companion.str
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.QueuedSignupWork
import com.pynapli.server.testsupport.RecordingAppleTokenClient
import com.pynapli.server.testsupport.TEST_CLIENT_IP
import com.pynapli.server.testsupport.signUpVerified
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.LinkSocialIdentityRequestDto
import com.pynapli.shared.network.dto.LinkedIdentitiesDto
import com.pynapli.shared.network.dto.SocialAccountAppealRequestDto
import com.pynapli.shared.network.dto.SocialNonceDto
import com.pynapli.shared.network.dto.SocialSignInConfigDto
import com.pynapli.shared.network.dto.SocialSignInRequestDto
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Continue with Google / Apple end to end against the real database and the real routes, with a fake
 * identity provider (generated keys, loopback JWKS) and a recording Apple token client.
 */
class SocialSignInIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val hasher = PasswordHasher(iterations = 1_000)
    private val authService = AuthService(JdbcAuthRepository(dataSource), passwordHasher = hasher)
    private val idp = FakeIdentityProvider()
    private val nonces = InMemorySocialNonceStore()
    private val apple = RecordingAppleTokenClient()
    private val seal = SocialTokenSeal("social-token-key-for-the-integration-tests-only")
    private val work = QueuedSignupWork()
    private val revoker = AppleGrantRevoker(apple, seal, work::launch)
    private val verifiedAddresses = mutableListOf<Pair<UUID, String>>()
    private val bothProviders = mapOf(SocialProvider.GOOGLE to idp.verifier(SocialProvider.GOOGLE), SocialProvider.APPLE to idp.verifier(SocialProvider.APPLE))

    private fun service(verifiers: Map<SocialProvider, SocialIdentityVerifier> = bothProviders) =
        SocialAuthService(
            verifiers = verifiers,
            nonceStore = nonces,
            repository = JdbcSocialIdentityRepository(dataSource),
            authService = authService,
            tokenSeal = seal,
            appleTokenClient = apple,
            appleGrantRevoker = revoker,
            launchBackground = work::launch,
            onAccountVerified = { id, email -> verifiedAddresses += id to email },
        )

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @AfterTest
    fun close() = idp.close()

    private fun ApplicationTestBuilder.serve(service: SocialAuthService = service()) {
        application {
            configureSerialization()
            configureStatusPages()
            configureSocialAuthRoutes(authService, service)
            configureAppealRoutes(authService, AppealService(JdbcAppealRepository(dataSource)), socialAuthService = service)
        }
    }

    private suspend fun HttpClient.nonce(): String = JSON.decodeFromString(SocialNonceDto.serializer(), post("/v1/auth/social/nonce").bodyAsText()).nonce

    private suspend fun HttpClient.signIn(
        provider: SocialProvider,
        token: String,
        nonce: String,
        birthdate: String? = null,
        code: String? = null,
    ): HttpResponse =
        post("/v1/auth/social/${provider.wire}") {
            contentType(ContentType.Application.Json)
            setBody(
                JSON.encodeToString(
                    SocialSignInRequestDto.serializer(),
                    SocialSignInRequestDto(
                        idToken = token,
                        nonce = nonce,
                        authorizationCode = code,
                        birthdate = birthdate,
                        confirmedAdult = birthdate != null,
                        acceptedTerms = birthdate != null,
                        acceptedPrivacy = birthdate != null,
                        platform = "android",
                        appVersion = "1.0",
                    ),
                ),
            )
        }

    /** A fresh nonce and a token carrying it; Apple tokens carry the hashed form, as clients send it. */
    private suspend fun HttpClient.attempt(
        provider: SocialProvider,
        subject: String,
        email: String? = null,
        verified: Boolean = true,
        birthdate: String? = null,
        code: String? = null,
    ): HttpResponse {
        val nonce = nonce()
        val claimNonce = if (provider == SocialProvider.APPLE) SocialIdentityVerifier.sha256Hex(nonce) else nonce
        val token = idp.idToken(provider, subject, claimNonce, email = email, emailVerified = email?.let { bool(verified) })
        return signIn(provider, token, nonce, birthdate, code)
    }

    private suspend fun HttpResponse.errorCode(): String =
        JSON
            .parseToJsonElement(bodyAsText())
            .jsonObject["code"]!!
            .jsonPrimitive.content

    private suspend fun session(response: HttpResponse): AuthSessionDto = JSON.decodeFromString(AuthSessionDto.serializer(), response.bodyAsText())

    @Test
    fun configReflectsTheProvidersThatAreOn() =
        testApplication {
            serve(service(verifiers = mapOf(SocialProvider.GOOGLE to idp.verifier(SocialProvider.GOOGLE))))
            val config = JSON.decodeFromString(SocialSignInConfigDto.serializer(), client.get("/v1/auth/social/config").bodyAsText())
            assertEquals(SocialSignInConfigDto(google = true, apple = false), config)
            val appleAttempt = client.signIn(SocialProvider.APPLE, "x.y.z", client.nonce())
            assertEquals(HttpStatusCode.NotFound, appleAttempt.status)
            assertEquals("SOCIAL_SIGN_IN_UNAVAILABLE", appleAttempt.errorCode())
        }

    @Test
    fun withEverythingOffNothingIsServed() =
        testApplication {
            serve(service(verifiers = emptyMap()))
            assertEquals(SocialSignInConfigDto(false, false), JSON.decodeFromString(SocialSignInConfigDto.serializer(), client.get("/v1/auth/social/config").bodyAsText()))
            assertEquals(HttpStatusCode.NotFound, client.post("/v1/auth/social/nonce").status)
            assertEquals(HttpStatusCode.NotFound, client.signIn(SocialProvider.GOOGLE, "x.y.z", "n").status)
        }

    @Test
    fun anUnknownIdentityWithoutTheAgeGateCreatesNothingAndSpendsTheNonce() =
        testApplication {
            serve()
            val nonce = client.nonce()
            val token = idp.idToken(SocialProvider.GOOGLE, "g-new", nonce, email = "new@gmail.com", emailVerified = bool(true))
            val first = client.signIn(SocialProvider.GOOGLE, token, nonce)
            assertEquals(HttpStatusCode.UnprocessableEntity, first.status)
            assertEquals("SOCIAL_SIGNUP_NEEDS_AGE_GATE", first.errorCode())
            assertEquals(0, count("SELECT count(*) FROM users"))

            // Resending the same token and nonce, now with the age gate, is refused: one answer per nonce.
            val replay = client.signIn(SocialProvider.GOOGLE, token, nonce, birthdate = ADULT)
            assertEquals(HttpStatusCode.Unauthorized, replay.status)
            assertEquals("INVALID_SOCIAL_TOKEN", replay.errorCode())
            assertEquals(0, count("SELECT count(*) FROM users"))
        }

    @Test
    fun anUnknownIdentityWithTheAgeGateGetsAnAccountAndASession() =
        testApplication {
            serve()
            val response = client.attempt(SocialProvider.GOOGLE, "g-1", email = "Jane@gmail.com", birthdate = ADULT)
            assertEquals(HttpStatusCode.OK, response.status)
            val userId = UUID.fromString(session(response).user.userId)

            assertEquals(Sha256.hash("jane@gmail.com"), string("SELECT email_hash FROM users WHERE id = ?", userId))
            assertNotNull(string("SELECT email_verified_at::text FROM users WHERE id = ?", userId))
            assertEquals("google", string("SELECT provider FROM auth_identities WHERE user_id = ?", userId))
            assertEquals("j•••@gmail.com", string("SELECT provider_email_hint FROM auth_identities WHERE user_id = ?", userId))
            assertNull(string("SELECT password_hash FROM auth_identities WHERE user_id = ?", userId))
            assertEquals(1, count("SELECT count(*) FROM profiles WHERE user_id = ?", userId))
            assertEquals(1, count("SELECT count(*) FROM discovery_settings WHERE user_id = ?", userId))
            // Never the provider's name: the Profile step sets one.
            assertNull(string("SELECT display_name FROM profiles WHERE user_id = ?", userId))
            // "Hide from people you know" is bound before the session is returned.
            assertEquals(listOf(userId to "jane@gmail.com"), verifiedAddresses)
        }

    @Test
    fun theSameIdentitySignsInAgainToTheSameAccount() =
        testApplication {
            serve()
            val created = session(client.attempt(SocialProvider.GOOGLE, "g-1", email = "jane@gmail.com", birthdate = ADULT))
            val again = client.attempt(SocialProvider.GOOGLE, "g-1", email = "jane@gmail.com")
            assertEquals(HttpStatusCode.OK, again.status)
            assertEquals(created.user.userId, session(again).user.userId)
            assertEquals(1, count("SELECT count(*) FROM users"))
        }

    @Test
    fun anUnderageBirthdayGetsTheEmailSignUpAnswerAndNoAccount() =
        testApplication {
            serve()
            val response = client.attempt(SocialProvider.GOOGLE, "g-young", email = "kid@gmail.com", birthdate = LocalDate.now().minusYears(17).toString())
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("AGE_RESTRICTED", response.errorCode())
            assertEquals(0, count("SELECT count(*) FROM users"))
        }

    @Test
    fun aVerifiedAddressOfAnExistingAccountIsNeverLinkedAutomatically() =
        testApplication {
            serve()
            val member = authService.signUpVerified("owner@gmail.com", PASSWORD, passwordHasher = hasher)
            val response = client.attempt(SocialProvider.GOOGLE, "g-owner", email = "owner@gmail.com", birthdate = ADULT)
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("SOCIAL_EMAIL_HAS_ACCOUNT", response.errorCode())
            assertEquals(1, count("SELECT count(*) FROM users"))
            assertEquals(0, count("SELECT count(*) FROM auth_identities WHERE provider = 'google'"))
            // Answered before the age gate: the owner isn't asked for a birthday first.
            assertEquals("SOCIAL_EMAIL_HAS_ACCOUNT", client.attempt(SocialProvider.GOOGLE, "g-owner", email = "owner@gmail.com").errorCode())
            assertEquals(member.user.userId, string("SELECT user_id::text FROM auth_identities WHERE provider = 'email'"))
        }

    @Test
    fun anEmailIdentityCountsEvenWithoutUsersEmailHashTheSameAsForEmailSignUp() =
        testApplication {
            serve()
            val member = authService.signUpVerified("legacy@gmail.com", PASSWORD, passwordHasher = hasher)
            // An account whose users.email_hash is gone but whose email identity still holds the address.
            exec("UPDATE users SET email_hash = NULL WHERE id = ?", UUID.fromString(member.user.userId))
            val emailHash = Sha256.hash("legacy@gmail.com")
            assertTrue(JdbcSocialIdentityRepository(dataSource).emailHasAccount(emailHash))
            assertTrue(JdbcEmailSignupRepository(dataSource).addressHasAccount(emailHash), "the same predicate on both sides")
            assertEquals("SOCIAL_EMAIL_HAS_ACCOUNT", client.attempt(SocialProvider.GOOGLE, "g-legacy", email = "legacy@gmail.com", birthdate = ADULT).errorCode())
            assertFalse(JdbcSocialIdentityRepository(dataSource).emailHasAccount(Sha256.hash("nobody@gmail.com")))
        }

    @Test
    fun anAddressTheProviderDoesNotVouchForNeverMatchesAnAccount() =
        testApplication {
            serve()
            authService.signUpVerified("owner@gmail.com", PASSWORD, passwordHasher = hasher)
            authService.signUpVerified("someone@yahoo.example", PASSWORD, passwordHasher = hasher)
            // Unverified Gmail, and a verified address Google is not authoritative for: no membership answer.
            assertEquals("SOCIAL_SIGNUP_NEEDS_AGE_GATE", client.attempt(SocialProvider.GOOGLE, "g-a", email = "owner@gmail.com", verified = false).errorCode())
            assertEquals("SOCIAL_SIGNUP_NEEDS_AGE_GATE", client.attempt(SocialProvider.GOOGLE, "g-b", email = "someone@yahoo.example").errorCode())
            // And creating it leaves the address with the existing account alone.
            val created = client.attempt(SocialProvider.GOOGLE, "g-b", email = "someone@yahoo.example", birthdate = ADULT)
            assertEquals(HttpStatusCode.OK, created.status)
            assertNull(string("SELECT email_hash FROM users WHERE id = ?", UUID.fromString(session(created).user.userId)))
        }

    @Test
    fun anAppleRelayAddressIsKeptOffTheAccount() =
        testApplication {
            serve()
            val relay = "abc123@privaterelay.appleid.com"
            authService.signUpVerified(relay, PASSWORD, passwordHasher = hasher)
            val response = client.attempt(SocialProvider.APPLE, "a-relay", email = relay, birthdate = ADULT)
            assertEquals(HttpStatusCode.OK, response.status)
            val userId = UUID.fromString(session(response).user.userId)
            assertNull(string("SELECT email_hash FROM users WHERE id = ?", userId))
            assertNull(string("SELECT email_verified_at::text FROM users WHERE id = ?", userId))
            assertTrue(verifiedAddresses.isEmpty(), "a relay address is nobody's known address")
        }

    @Test
    fun suspendedAccountsAreRefusedAndAppealThroughAFreshProviderSignIn() =
        testApplication {
            serve()
            val userId = UUID.fromString(session(client.attempt(SocialProvider.GOOGLE, "g-1", email = "jane@gmail.com", birthdate = ADULT)).user.userId)
            exec("UPDATE users SET status = 'suspended' WHERE id = ?", userId)

            val refused = client.attempt(SocialProvider.GOOGLE, "g-1", email = "jane@gmail.com")
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals("ACCOUNT_NOT_ACTIVE", refused.errorCode())

            val nonce = client.nonce()
            val appeal =
                client.post("/v1/auth/account-appeal/social/google") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        JSON.encodeToString(
                            SocialAccountAppealRequestDto.serializer(),
                            SocialAccountAppealRequestDto(idp.idToken(SocialProvider.GOOGLE, "g-1", nonce), nonce, "This was a mistake."),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, appeal.status)
            assertEquals(1, count("SELECT count(*) FROM moderation_appeals WHERE user_id = ? AND action_type = 'account_suspension'", userId))
        }

    @Test
    fun anActiveAccountCannotFileAnAccountAppeal() =
        testApplication {
            serve()
            client.attempt(SocialProvider.GOOGLE, "g-1", birthdate = ADULT)
            val nonce = client.nonce()
            val appeal =
                client.post("/v1/auth/account-appeal/social/google") {
                    contentType(ContentType.Application.Json)
                    setBody(JSON.encodeToString(SocialAccountAppealRequestDto.serializer(), SocialAccountAppealRequestDto(idp.idToken(SocialProvider.GOOGLE, "g-1", nonce), nonce, "x")))
                }
            assertEquals(HttpStatusCode.Conflict, appeal.status)
            assertEquals("ACCOUNT_NOT_RESTRICTED", appeal.errorCode())
        }

    @Test
    fun linkingAddsAMethodUnlinkingRemovesItButNeverTheLastOne() =
        testApplication {
            serve()
            val member = authService.signUpVerified("member@example.com", PASSWORD, passwordHasher = hasher)
            val token = member.accessToken

            val linked = client.link(token, SocialProvider.GOOGLE, "g-member", email = "member@gmail.com")
            assertEquals(HttpStatusCode.OK, linked.status)
            val identities = JSON.decodeFromString(LinkedIdentitiesDto.serializer(), linked.bodyAsText())
            assertEquals(listOf("email", "google"), identities.identities.map { it.provider })
            assertTrue(identities.hasPassword)
            // Linking never changes the account's address.
            assertEquals(Sha256.hash("member@example.com"), string("SELECT email_hash FROM users WHERE id = ?", UUID.fromString(member.user.userId)))

            // The linked Google identity now signs in to the same account.
            assertEquals(member.user.userId, session(client.attempt(SocialProvider.GOOGLE, "g-member", email = "member@gmail.com")).user.userId)

            assertEquals(HttpStatusCode.OK, client.delete("/v1/auth/identities/google") { bearerAuth(token) }.status)
            val notLinked = client.delete("/v1/auth/identities/google") { bearerAuth(token) }
            assertEquals("SOCIAL_IDENTITY_NOT_LINKED", notLinked.errorCode())
        }

    @Test
    fun aSocialOnlyAccountCannotRemoveItsOnlySignInMethod() =
        testApplication {
            serve()
            val token = session(client.attempt(SocialProvider.APPLE, "a-1", birthdate = ADULT)).accessToken
            val response = client.delete("/v1/auth/identities/apple") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("LAST_SIGN_IN_METHOD", response.errorCode())
            val identities = JSON.decodeFromString(LinkedIdentitiesDto.serializer(), client.get("/v1/auth/identities") { bearerAuth(token) }.bodyAsText())
            assertEquals(listOf("apple"), identities.identities.map { it.provider })
            assertFalse(identities.hasPassword)
        }

    @Test
    fun anIdentityThatBelongsToAnotherAccountCannotBeLinked() =
        testApplication {
            serve()
            client.attempt(SocialProvider.GOOGLE, "g-taken", birthdate = ADULT)
            val member = authService.signUpVerified("member@example.com", PASSWORD, passwordHasher = hasher)
            val response = client.link(member.accessToken, SocialProvider.GOOGLE, "g-taken")
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("SOCIAL_IDENTITY_IN_USE", response.errorCode())
            assertEquals(1, count("SELECT count(*) FROM auth_identities WHERE provider = 'google'"))
        }

    @Test
    fun aBadProviderTokenWhileLinkingIs400SoTheClientNeverReplaysIt() =
        testApplication {
            serve()
            val member = authService.signUpVerified("member@example.com", PASSWORD, passwordHasher = hasher)
            val nonce = client.nonce()
            val response =
                client.post("/v1/auth/identities/google") {
                    bearerAuth(member.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        JSON.encodeToString(
                            LinkSocialIdentityRequestDto.serializer(),
                            LinkSocialIdentityRequestDto(idp.idToken(SocialProvider.GOOGLE, "g-x", "not-the-nonce"), nonce),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("INVALID_SOCIAL_TOKEN", response.errorCode())
            // The unauthenticated sign-in keeps 401 for the same failure.
            assertEquals(HttpStatusCode.Unauthorized, client.signIn(SocialProvider.GOOGLE, idp.idToken(SocialProvider.GOOGLE, "g-x", "not-the-nonce"), client.nonce()).status)
        }

    @Test
    fun linkingNeedsASession() =
        testApplication {
            serve()
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/auth/identities").status)
            assertEquals(HttpStatusCode.Unauthorized, client.delete("/v1/auth/identities/google").status)
        }

    @Test
    fun appleSignUpStoresTheRefreshTokenSealedAndDeletionRevokesIt() =
        testApplication {
            serve()
            val response = client.attempt(SocialProvider.APPLE, "a-del", email = "a@icloud.com", birthdate = ADULT, code = "apple-code-1")
            assertEquals(HttpStatusCode.OK, response.status)
            work.runAll()
            assertEquals(listOf(Triple(FakeIdentityProvider.APPLE_CLIENT_ID, "apple-code-1", "a-del")), apple.exchanges)
            val userId = UUID.fromString(session(response).user.userId)
            val sealed = bytes("SELECT apple_refresh_token_sealed FROM auth_identities WHERE user_id = ?", userId)!!
            assertFalse(String(sealed, Charsets.ISO_8859_1).contains("apple-refresh-token-1"), "stored encrypted, never in the clear")

            val lifecycle =
                AccountLifecycleService(
                    repository = JdbcAccountLifecycleRepository(dataSource, ContactBlockTokens.localDevelopment()),
                    revokeAppleGrants = revoker::revokeLater,
                )
            lifecycle.deleteAccount(userId, "DELETE")
            work.runAll()

            assertEquals(listOf(FakeIdentityProvider.APPLE_CLIENT_ID to "apple-refresh-token-1"), apple.revocations)
            assertNull(bytes("SELECT apple_refresh_token_sealed FROM auth_identities WHERE user_id = ?", userId))
            assertTrue(string("SELECT provider_subject FROM auth_identities WHERE user_id = ?", userId)!!.startsWith("deleted:"))
            // After deletion the same Apple identity is a stranger again.
            assertEquals("SOCIAL_SIGNUP_NEEDS_AGE_GATE", client.attempt(SocialProvider.APPLE, "a-del").errorCode())
        }

    @Test
    fun unlinkingAppleRevokesItsGrant() =
        testApplication {
            serve()
            val member = authService.signUpVerified("member@example.com", PASSWORD, passwordHasher = hasher)
            assertEquals(HttpStatusCode.OK, client.link(member.accessToken, SocialProvider.APPLE, "a-link", code = "apple-code-2").status)
            work.runAll()
            assertEquals(HttpStatusCode.OK, client.delete("/v1/auth/identities/apple") { bearerAuth(member.accessToken) }.status)
            work.runAll()
            assertEquals(listOf(FakeIdentityProvider.APPLE_CLIENT_ID to "apple-refresh-token-1"), apple.revocations)
        }

    private fun realAppleClient(subject: String): HttpAppleTokenClient {
        val ecKeys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(ecKeys.private.encoded) + "\n-----END PRIVATE KEY-----"
        val idToken = "e30.${FakeIdentityProvider.b64("""{"sub":"$subject","aud":"${FakeIdentityProvider.APPLE_CLIENT_ID}"}""".toByteArray())}.sig"
        idp.tokenResponse = 200 to """{"refresh_token":"r-late","id_token":"$idToken","token_type":"bearer"}"""
        return HttpAppleTokenClient(
            teamId = "TEAM123456",
            keyId = "KEY1234567",
            privateKeyPem = pem,
            tokenUrl = "${idp.baseUrl}/auth/token",
            revokeUrl = "${idp.baseUrl}/auth/revoke",
        )
    }

    private fun serviceWithRealApple(subject: String): SocialAuthService {
        val client = realAppleClient(subject)
        return SocialAuthService(
            verifiers = bothProviders,
            nonceStore = nonces,
            repository = JdbcSocialIdentityRepository(dataSource),
            authService = authService,
            tokenSeal = seal,
            appleTokenClient = client,
            appleGrantRevoker = AppleGrantRevoker(client, seal, work::launch),
            launchBackground = work::launch,
        )
    }

    @Test
    fun aGrantThatArrivesAfterUnlinkIsRevokedAtOnce() =
        testApplication {
            serve(serviceWithRealApple("a-late"))
            val member = authService.signUpVerified("member@example.com", PASSWORD, passwordHasher = hasher)
            assertEquals(HttpStatusCode.OK, client.link(member.accessToken, SocialProvider.APPLE, "a-late", code = "late-code").status)
            // Unlinked before the queued code exchange runs: there is no stored grant to revoke yet.
            assertEquals(HttpStatusCode.OK, client.delete("/v1/auth/identities/apple") { bearerAuth(member.accessToken) }.status)
            assertTrue(idp.revokeRequests.isEmpty())

            work.runAll()

            assertEquals(listOf("late-code"), idp.tokenRequests.map { it["code"] })
            assertEquals(listOf("r-late"), idp.revokeRequests.map { it["token"] })
            assertEquals(0, count("SELECT count(*) FROM auth_identities WHERE apple_refresh_token_sealed IS NOT NULL"))
        }

    @Test
    fun aGrantThatArrivesAfterDeletionIsRevokedAtOnce() =
        testApplication {
            serve(serviceWithRealApple("a-gone"))
            val userId = UUID.fromString(session(client.attempt(SocialProvider.APPLE, "a-gone", birthdate = ADULT, code = "gone-code")).user.userId)
            AccountLifecycleService(JdbcAccountLifecycleRepository(dataSource, ContactBlockTokens.localDevelopment())).deleteAccount(userId, "DELETE")

            work.runAll()

            assertEquals(listOf("r-late"), idp.revokeRequests.map { it["token"] })
            assertNull(bytes("SELECT apple_refresh_token_sealed FROM auth_identities WHERE user_id = ?", userId))
        }

    @Test
    fun theRealAppleClientSignsItsSecretAndSendsWhatAppleDocuments() =
        runBlocking {
            val ecKeys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(ecKeys.private.encoded) + "\n-----END PRIVATE KEY-----"
            val client =
                HttpAppleTokenClient(
                    teamId = "TEAM123456",
                    keyId = "KEY1234567",
                    privateKeyPem = pem,
                    tokenUrl = "${idp.baseUrl}/auth/token",
                    revokeUrl = "${idp.baseUrl}/auth/revoke",
                )
            val idToken = "e30.${FakeIdentityProvider.b64("""{"sub":"a-1","aud":"${FakeIdentityProvider.APPLE_CLIENT_ID}"}""".toByteArray())}.sig"
            idp.tokenResponse = 200 to """{"refresh_token":"r-1","id_token":"$idToken","token_type":"bearer"}"""
            assertEquals("r-1", client.exchangeCode(FakeIdentityProvider.APPLE_CLIENT_ID, "code-1", "a-1"))
            // A code for someone else's Apple account is never stored against this identity.
            assertNull(client.exchangeCode(FakeIdentityProvider.APPLE_CLIENT_ID, "code-2", "a-OTHER"))
            assertTrue(client.revoke(FakeIdentityProvider.APPLE_CLIENT_ID, "r-1"))

            val exchange = idp.tokenRequests.first()
            assertEquals("authorization_code", exchange["grant_type"])
            assertEquals("code-1", exchange["code"])
            assertEquals(FakeIdentityProvider.APPLE_CLIENT_ID, exchange["client_id"])
            val revoke = idp.revokeRequests.single()
            assertEquals("r-1", revoke["token"])
            assertEquals("refresh_token", revoke["token_type_hint"])

            val secret = revoke.getValue("client_secret").split('.')
            val header = JSON.parseToJsonElement(Base64.getUrlDecoder().decode(secret[0]).decodeToString()).jsonObject
            val claims = JSON.parseToJsonElement(Base64.getUrlDecoder().decode(secret[1]).decodeToString()).jsonObject
            assertEquals("ES256", header["alg"]!!.jsonPrimitive.content)
            assertEquals("KEY1234567", header["kid"]!!.jsonPrimitive.content)
            assertEquals("TEAM123456", claims["iss"]!!.jsonPrimitive.content)
            assertEquals("https://appleid.apple.com", claims["aud"]!!.jsonPrimitive.content)
            assertEquals(FakeIdentityProvider.APPLE_CLIENT_ID, claims["sub"]!!.jsonPrimitive.content)
            val signature = Base64.getUrlDecoder().decode(secret[2])
            assertEquals(64, signature.size, "JWS ES256 is raw R||S, not DER")
            val verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
            verifier.initVerify(ecKeys.public)
            verifier.update("${secret[0]}.${secret[1]}".toByteArray())
            assertTrue(verifier.verify(signature))
        }

    @Test
    fun emailSignUpForASocialOnlyAddressSendsTheNeutralNoticeAndNoCode() =
        testApplication {
            serve()
            client.attempt(SocialProvider.GOOGLE, "g-1", email = "jane@gmail.com", birthdate = ADULT)
            val mailer = CapturingSignupMailer()
            val signupWork = QueuedSignupWork()
            val signup =
                EmailSignupService(
                    repository = JdbcEmailSignupRepository(dataSource),
                    mailer = mailer,
                    authService = authService,
                    passwordHasher = hasher,
                    launchSignupWork = signupWork::launch,
                )
            val forMember = signup.startSignup("jane@gmail.com", PASSWORD, ADULT, true, true, true, "phone", TEST_CLIENT_IP)
            val forNewcomer = signup.startSignup("newcomer@gmail.com", PASSWORD, ADULT, true, true, true, "phone", TEST_CLIENT_IP)
            // The same answer for both (only the random token differs).
            assertEquals(forMember.copy(signupToken = ""), forNewcomer.copy(signupToken = ""))
            signupWork.runAll()
            assertEquals(listOf("jane@gmail.com"), mailer.notices)
            assertEquals(listOf("newcomer@gmail.com"), mailer.codes.map { it.first })
            assertEquals(0, count("SELECT count(*) FROM pending_email_signups WHERE email_hash = ?", Sha256.hash("jane@gmail.com")))
        }

    @Test
    fun theDataExportListsSignInMethodsButNoSecrets() =
        testApplication {
            serve()
            val userId = UUID.fromString(session(client.attempt(SocialProvider.APPLE, "a-export", email = "a@icloud.com", birthdate = ADULT, code = "c")).user.userId)
            work.runAll()
            val text =
                run {
                    JdbcAccountDataExportArchiver(dataSource, contactBlockTokens = ContactBlockTokens.localDevelopment()).buildArchive(userId)
                }.bytes.decodeToString()
            val methods = JSON.parseToJsonElement(text).jsonObject["signInMethods"].toString()
            assertTrue(methods.contains("apple"))
            listOf("a-export", "apple_refresh_token", "apple-refresh-token-1", "provider_subject").forEach { secret ->
                assertFalse(text.contains(secret), "export must not contain $secret")
            }
        }

    @Test
    fun theNonceStoreHoldsOnlyHashesAndSpendsEachNonceOnce() =
        runBlocking {
            val store = InMemorySocialNonceStore()
            store.remember("n-1", SocialAuthService.NONCE_LIFETIME)
            assertTrue(store.consume("n-1"))
            assertFalse(store.consume("n-1"))
            assertFalse(store.consume("never-issued"))
        }

    private suspend fun HttpClient.link(
        accessToken: String,
        provider: SocialProvider,
        subject: String,
        email: String? = null,
        code: String? = null,
    ): HttpResponse {
        val nonce = nonce()
        val claimNonce = if (provider == SocialProvider.APPLE) SocialIdentityVerifier.sha256Hex(nonce) else nonce
        val token = idp.idToken(provider, subject, claimNonce, email = email, emailVerified = email?.let { str("true") })
        return post("/v1/auth/identities/${provider.wire}") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(JSON.encodeToString(LinkSocialIdentityRequestDto.serializer(), LinkSocialIdentityRequestDto(token, nonce, code)))
        }
    }

    private fun count(
        sql: String,
        vararg args: Any,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }

    private fun string(
        sql: String,
        vararg args: Any,
    ): String? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun bytes(
        sql: String,
        vararg args: Any,
    ): ByteArray? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rs -> if (rs.next()) rs.getBytes(1) else null }
            }
        }

    private fun exec(
        sql: String,
        vararg args: Any,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        const val ADULT = "1990-01-01"
        const val PASSWORD = "social-test-password-1"
    }
}
