package com.pynapli.server.auth.security

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RefreshReplaySealTest {
    private val consumed = TokenGenerator.newToken()
    private val sessionId = UUID.randomUUID()
    private val access = TokenGenerator.newToken()
    private val refresh = TokenGenerator.newToken()

    @Test
    fun opensWithTheConsumedTokenOnTheSameSession() {
        val sealed = RefreshReplaySeal.seal(consumed, sessionId, access, refresh)

        assertEquals(access to refresh, RefreshReplaySeal.open(consumed, sessionId, sealed.nonce, sealed.ciphertext))
    }

    @Test
    fun anyOtherTokenCannotOpenIt() {
        val sealed = RefreshReplaySeal.seal(consumed, sessionId, access, refresh)

        assertNull(RefreshReplaySeal.open(TokenGenerator.newToken(), sessionId, sealed.nonce, sealed.ciphertext))
        // The stored lookup hash of the token is not the key either.
        assertNull(RefreshReplaySeal.open(Sha256.hash(consumed), sessionId, sealed.nonce, sealed.ciphertext))
        // Nor is the new refresh token (what a client holds after a successful rotation).
        assertNull(RefreshReplaySeal.open(refresh, sessionId, sealed.nonce, sealed.ciphertext))
    }

    @Test
    fun itOnlyOpensOnTheSessionItWasSealedFor() {
        val sealed = RefreshReplaySeal.seal(consumed, sessionId, access, refresh)

        assertNull(RefreshReplaySeal.open(consumed, UUID.randomUUID(), sealed.nonce, sealed.ciphertext))
    }

    @Test
    fun aTamperedCiphertextOrNonceDoesNotOpen() {
        val sealed = RefreshReplaySeal.seal(consumed, sessionId, access, refresh)

        for (index in sealed.ciphertext.indices) {
            val tampered = sealed.ciphertext.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertNull(RefreshReplaySeal.open(consumed, sessionId, sealed.nonce, tampered), "flipped ciphertext byte $index")
        }
        val tamperedNonce = sealed.nonce.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(RefreshReplaySeal.open(consumed, sessionId, tamperedNonce, sealed.ciphertext))
        assertNull(RefreshReplaySeal.open(consumed, sessionId, sealed.nonce, sealed.ciphertext.copyOf(sealed.ciphertext.size - 1)))
        assertNull(RefreshReplaySeal.open(consumed, sessionId, ByteArray(0), sealed.ciphertext))
        assertNull(RefreshReplaySeal.open(consumed, sessionId, sealed.nonce, ByteArray(0)))
    }

    @Test
    fun everySealUsesAFreshNonceAndHidesTheTokens() {
        val first = RefreshReplaySeal.seal(consumed, sessionId, access, refresh)
        val second = RefreshReplaySeal.seal(consumed, sessionId, access, refresh)

        assertEquals(12, first.nonce.size)
        assertFalse(first.nonce.contentEquals(second.nonce))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
        val stored = String(first.ciphertext, Charsets.ISO_8859_1)
        assertFalse(stored.contains(access))
        assertFalse(stored.contains(refresh))
        // Both still open to the same tokens.
        assertEquals(access to refresh, RefreshReplaySeal.open(consumed, sessionId, second.nonce, second.ciphertext))
    }
}
