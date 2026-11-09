package com.pynapli.server.safety

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the App Check verifier wrapper contract independently of Google's network/JWKS: a blank token
 * is rejected without invoking the verifier, a verifier exception means "not attested", and success
 * means attested. (The real RS256/JWKS validation is delegated to TokenVerifier in [forProject].)
 */
class FirebaseAppCheckVerifierTest {
    @Test
    fun rejectsNullOrBlankWithoutInvokingTheVerifier() =
        runBlocking {
            var invoked = false
            val verifier =
                FirebaseAppCheckVerifier {
                    invoked = true
                    validClaims()
                }
            assertFalse(verifier.verify(null))
            assertFalse(verifier.verify("   "))
            assertFalse(invoked)
        }

    @Test
    fun acceptsWhenTheTokenVerifierSucceeds() =
        runBlocking {
            val verifier = FirebaseAppCheckVerifier { validClaims() }
            assertTrue(verifier.verify("a-real-looking-token"))
        }

    @Test
    fun rejectsWhenTheTokenVerifierThrows() =
        runBlocking {
            val verifier = FirebaseAppCheckVerifier { error("invalid signature") }
            assertFalse(verifier.verify("a-tampered-token"))
        }

    @Test
    fun rejectsWhenAudienceDoesNotContainTheProject() =
        runBlocking {
            val verifier =
                FirebaseAppCheckVerifier {
                    validClaims(audiences = listOf("projects/other"))
                }

            assertFalse(verifier.verify("wrong-project-token"))
        }

    @Test
    fun rejectsWhenSubjectIsMissing() =
        runBlocking {
            val verifier = FirebaseAppCheckVerifier { validClaims(subject = "   ") }

            assertFalse(verifier.verify("missing-subject-token"))
        }

    @Test
    fun acceptsWhenAudienceContainsTheProject() =
        runBlocking {
            val verifier =
                FirebaseAppCheckVerifier {
                    validClaims(audiences = listOf("other", "projects/209241465883"))
                }

            assertTrue(verifier.verify("list-audience-token"))
        }

    @Test
    fun forProjectBuildsAVerifierWithoutTouchingTheNetwork() {
        // Construction is lazy — certs are only fetched on verify(), so this does no I/O.
        assertNotNull(FirebaseAppCheckVerifier.forProject("209241465883"))
    }

    private fun validClaims(
        audiences: List<String> = listOf("projects/209241465883"),
        subject: String? = "1:209241465883:android:5ef3d6abdcd4dde6c084be",
    ): VerifiedAppCheckClaims =
        VerifiedAppCheckClaims(
            audiences = audiences,
            subject = subject,
            expectedAudience = "projects/209241465883",
        )
}
