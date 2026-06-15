package com.pynapli.server.safety

import kotlinx.coroutines.runBlocking
import org.junit.Test
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
            val verifier = FirebaseAppCheckVerifier { invoked = true }
            assertFalse(verifier.verify(null))
            assertFalse(verifier.verify("   "))
            assertFalse(invoked)
        }

    @Test
    fun acceptsWhenTheTokenVerifierSucceeds() =
        runBlocking {
            val verifier = FirebaseAppCheckVerifier { /* no throw = valid */ }
            assertTrue(verifier.verify("a-real-looking-token"))
        }

    @Test
    fun rejectsWhenTheTokenVerifierThrows() =
        runBlocking {
            val verifier = FirebaseAppCheckVerifier { error("invalid signature") }
            assertFalse(verifier.verify("a-tampered-token"))
        }

    @Test
    fun forProjectBuildsAVerifierWithoutTouchingTheNetwork() {
        // Construction is lazy — certs are only fetched on verify(), so this does no I/O.
        assertNotNull(FirebaseAppCheckVerifier.forProject("209241465883"))
    }
}
