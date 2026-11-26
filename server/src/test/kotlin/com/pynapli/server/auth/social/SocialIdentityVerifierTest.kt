package com.pynapli.server.auth.social

import com.pynapli.server.auth.AuthException
import com.pynapli.server.testsupport.FakeIdentityProvider
import com.pynapli.server.testsupport.FakeIdentityProvider.Companion.bool
import com.pynapli.server.testsupport.FakeIdentityProvider.Companion.str
import kotlinx.coroutines.runBlocking
import java.security.KeyPairGenerator
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The verifier against a fake provider: real RS256 signatures over generated keys, a JWKS fetched over
 * loopback HTTP by the real TokenVerifier, and every claim rule from Google's and Apple's docs.
 */
class SocialIdentityVerifierTest {
    private val provider = FakeIdentityProvider()
    private val google = provider.verifier(SocialProvider.GOOGLE)
    private val apple = provider.verifier(SocialProvider.APPLE)

    @AfterTest
    fun close() = provider.close()

    private fun rejected(block: suspend () -> Unit) {
        val error = assertFailsWith<AuthException> { runBlocking { block() } }
        assertEquals("INVALID_SOCIAL_TOKEN", error.code)
    }

    @Test
    fun aValidGoogleTokenVerifies() =
        runBlocking {
            val token = provider.idToken(SocialProvider.GOOGLE, "g-1", NONCE, email = "Jane@Gmail.com", emailVerified = bool(true))
            val identity = google.verify(token, NONCE)
            assertEquals("g-1", identity.subject)
            assertEquals(FakeIdentityProvider.GOOGLE_CLIENT_ID, identity.audience)
            assertEquals("jane@gmail.com", identity.authoritativeEmail)
            assertEquals("j•••@gmail.com", identity.emailHint)
        }

    @Test
    fun bothGoogleIssuerFormsAreAccepted() =
        runBlocking {
            val token = provider.idToken(SocialProvider.GOOGLE, "g-1", NONCE, issuer = "accounts.google.com")
            assertEquals("g-1", google.verify(token, NONCE).subject)
        }

    @Test
    fun wrongAudienceIsRejected() = rejected { google.verify(provider.idToken(SocialProvider.GOOGLE, "g-1", NONCE, audience = "someone-elses-app"), NONCE) }

    @Test
    fun wrongIssuerIsRejected() = rejected { google.verify(provider.idToken(SocialProvider.GOOGLE, "g-1", NONCE, issuer = "https://evil.example"), NONCE) }

    @Test
    fun anAppleTokenIsNotAGoogleToken() = rejected { google.verify(provider.idToken(SocialProvider.APPLE, "a-1", NONCE, audience = FakeIdentityProvider.GOOGLE_CLIENT_ID), NONCE) }

    @Test
    fun expiredTokenIsRejected() =
        rejected {
            google.verify(provider.idToken(SocialProvider.GOOGLE, "g-1", NONCE, issuedAt = Instant.now().minusSeconds(7200), expiresAt = Instant.now().minusSeconds(3600)), NONCE)
        }

    @Test
    fun aTokenWithoutExpiryIsRejected() = rejected { google.verify(provider.idToken(SocialProvider.GOOGLE, "g-1", NONCE, expiresAt = null), NONCE) }

    @Test
    fun aTokenIssuedInTheFutureIsRejected() =
        rejected { google.verify(provider.idToken(SocialProvider.GOOGLE, "g-1", NONCE, issuedAt = Instant.now().plusSeconds(3600)), NONCE) }

    @Test
    fun aDifferentNonceIsRejected() = rejected { google.verify(provider.idToken(SocialProvider.GOOGLE, "g-1", "another-nonce"), NONCE) }

    @Test
    fun aMissingNonceIsRejected() = rejected { google.verify(provider.idToken(SocialProvider.GOOGLE, "g-1", null), NONCE) }

    @Test
    fun googleDoesNotAcceptTheHashedNonceForm() =
        rejected { google.verify(provider.idToken(SocialProvider.GOOGLE, "g-1", SocialIdentityVerifier.sha256Hex(NONCE)), NONCE) }

    @Test
    fun appleAcceptsTheHashedAndTheRawNonce() =
        runBlocking {
            val hashed = provider.idToken(SocialProvider.APPLE, "a-1", SocialIdentityVerifier.sha256Hex(NONCE))
            val raw = provider.idToken(SocialProvider.APPLE, "a-1", NONCE)
            assertEquals("a-1", apple.verify(hashed, NONCE).subject)
            assertEquals("a-1", apple.verify(raw, NONCE).subject)
        }

    @Test
    fun aTokenSignedByAnUnpublishedKeyIsRejected() =
        rejected {
            val stranger = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            google.verify(provider.idToken(SocialProvider.GOOGLE, "g-1", NONCE, signWith = stranger.private), NONCE)
        }

    @Test
    fun aTamperedPayloadIsRejected() =
        rejected {
            val token = provider.idToken(SocialProvider.GOOGLE, "g-1", NONCE)
            val parts = token.split('.')
            val forged =
                provider
                    .idToken(SocialProvider.GOOGLE, "someone-else", NONCE)
                    .split('.')[1]
            google.verify("${parts[0]}.$forged.${parts[2]}", NONCE)
        }

    @Test
    fun theNoneAlgorithmIsRejected() =
        rejected {
            val parts = provider.idToken(SocialProvider.GOOGLE, "g-1", NONCE).split('.')
            val header = FakeIdentityProvider.b64("""{"alg":"none","kid":"kid-1"}""".toByteArray())
            google.verify("$header.${parts[1]}.", NONCE)
        }

    @Test
    fun aRotatedKeyIsPickedUpOnTheFirstUnknownKid() =
        runBlocking {
            // Warm the one-hour key cache, then rotate: the new kid is unknown to the cached set.
            google.verify(provider.idToken(SocialProvider.GOOGLE, "g-1", NONCE), NONCE)
            provider.addKey("kid-2", publish = true)
            val identity = google.verify(provider.idToken(SocialProvider.GOOGLE, "g-2", NONCE, kid = "kid-2"), NONCE)
            assertEquals("g-2", identity.subject)
        }

    @Test
    fun aGoogleAddressIsAuthoritativeOnlyForGmailOrAHostedDomain() =
        runBlocking {
            fun token(
                email: String,
                verified: Boolean,
                hd: String? = null,
            ) = provider.idToken(
                SocialProvider.GOOGLE,
                "g-1",
                NONCE,
                email = email,
                emailVerified = bool(verified),
                extra = hd?.let { mapOf("hd" to str(it)) }.orEmpty(),
            )
            assertEquals("a@gmail.com", google.verify(token("a@gmail.com", true), NONCE).authoritativeEmail)
            assertEquals("a@corp.example", google.verify(token("a@corp.example", true, hd = "corp.example"), NONCE).authoritativeEmail)
            // Verified once, but Google isn't authoritative for it: its owner may have changed since.
            assertNull(google.verify(token("a@yahoo.example", true), NONCE).authoritativeEmail)
            assertNull(google.verify(token("a@gmail.com", false), NONCE).authoritativeEmail)
        }

    @Test
    fun appleBooleansMayBeStringsAndRelayAddressesAreNeverAuthoritative() =
        runBlocking {
            val real = apple.verify(provider.idToken(SocialProvider.APPLE, "a-1", NONCE, email = "a@icloud.com", emailVerified = str("true")), NONCE)
            assertEquals("a@icloud.com", real.authoritativeEmail)
            assertFalse(real.privateRelay)

            val flagged =
                apple.verify(
                    provider.idToken(
                        SocialProvider.APPLE,
                        "a-2",
                        NONCE,
                        email = "x1@privaterelay.appleid.com",
                        emailVerified = bool(true),
                        extra = mapOf("is_private_email" to str("true")),
                    ),
                    NONCE,
                )
            assertTrue(flagged.privateRelay)
            assertNull(flagged.authoritativeEmail)

            // The claim is sometimes missing for relay addresses: the domain decides.
            val unflagged = apple.verify(provider.idToken(SocialProvider.APPLE, "a-3", NONCE, email = "x2@privaterelay.appleid.com", emailVerified = str("true")), NONCE)
            assertTrue(unflagged.privateRelay)
            assertNull(unflagged.authoritativeEmail)

            val unverified = apple.verify(provider.idToken(SocialProvider.APPLE, "a-4", NONCE, email = "b@icloud.com", emailVerified = str("false")), NONCE)
            assertNull(unverified.authoritativeEmail)
        }

    @Test
    fun aTokenListingSeveralAudiencesIsRejected() =
        rejected {
            // Built by hand: the helper writes a single aud.
            val token = provider.idToken(SocialProvider.GOOGLE, "g-1", NONCE)
            val parts = token.split('.')
            val payload =
                java.util.Base64
                    .getUrlDecoder()
                    .decode(parts[1])
                    .decodeToString()
                    .replace(
                        "\"aud\":\"${FakeIdentityProvider.GOOGLE_CLIENT_ID}\"",
                        "\"aud\":[\"${FakeIdentityProvider.GOOGLE_CLIENT_ID}\",\"other\"]",
                    )
            val input = "${parts[0]}.${FakeIdentityProvider.b64(payload.toByteArray())}"
            val signer = java.security.Signature.getInstance("SHA256withRSA")
            signer.initSign(provider.privateKey("kid-1"))
            signer.update(input.toByteArray())
            google.verify("$input.${FakeIdentityProvider.b64(signer.sign())}", NONCE)
        }

    @Test
    fun blankInputsAreRejectedWithoutANetworkCall() {
        rejected { google.verify("", NONCE) }
        rejected { google.verify("a.b.c", "") }
    }

    @Test
    fun emailsAreMaskedToTheFirstCharacterAndDomain() {
        assertEquals("j•••@gmail.com", maskEmail("jane@gmail.com"))
        assertEquals("x•••@privaterelay.appleid.com", maskEmail("x1@privaterelay.appleid.com"))
    }

    private companion object {
        const val NONCE = "server-nonce-123"
    }
}
