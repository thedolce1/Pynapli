package com.pynapli.shared.auth

import com.pynapli.shared.network.PynapliJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers `SessionPayloadCodec` against an **out-of-band** cipher — one whose output is a reference
 * rather than ciphertext, with the secret held elsewhere.
 *
 * The existing `SessionPayloadCodecTest` only exercises in-band ciphers (Android keeps the ciphertext
 * in the row). iOS is the other shape: `KeychainSessionTokenCipher` puts the token in the Keychain and
 * stores only a sentinel, so its failure modes are different — the row alone can never reconstruct the
 * session, and the side store can vanish independently of the row. These cases pin the behaviour the
 * iOS design depends on, on a target that actually executes.
 *
 * Not covered here (no JVM SQLDelight driver in `:shared`, so `PynapliDatabase` cannot be built in a
 * common test): that `SqlDelightSessionStore.clear()` actually calls `forgetStoredSecret()`. That call
 * is compile-verified only — worth re-checking by hand on the Mac, because if it regressed the symptom
 * is an orphaned token on the device, which nothing visible would reveal.
 */
class IndirectionCipherCodecTest {
    private val payload =
        StoredSessionPayload(
            accessToken = "access-token-1",
            refreshToken = "refresh-token-1",
            userId = "user-1",
            userStatus = "active",
        )

    @Test
    fun storedRowHoldsOnlyAReferenceAndNoSecret() {
        val keychain = FakeKeychain()
        val codec = SessionPayloadCodec(IndirectionCipher(keychain), PynapliJson)

        val row = codec.encode(payload)

        assertEquals(SENTINEL, row, "the row must be the bare sentinel")
        assertFalse(row.contains("access-token-1"))
        assertFalse(row.contains("refresh-token-1"))
        // ...and the secret really is in the side store, not merely absent from the row.
        assertTrue(keychain.stored.orEmpty().contains("access-token-1"))
    }

    @Test
    fun referenceRoundTripsThroughTheSideStore() {
        val codec = SessionPayloadCodec(IndirectionCipher(FakeKeychain()), PynapliJson)

        val decoded = codec.decode(codec.encode(payload))

        assertEquals(payload, decoded.payload)
        assertFalse(decoded.needsEncryptionUpgrade)
    }

    /**
     * Keychain cleared, or the device restored from a backup that carried the database but not the
     * Keychain. The row survives and is meaningless on its own — the user must land signed out, and
     * crucially the codec must NOT mistake this for a legacy plaintext row and try to "upgrade" it.
     */
    @Test
    fun missingSideStoreEntrySignsTheUserOutWithoutAnUpgradeAttempt() {
        val keychain = FakeKeychain()
        val codec = SessionPayloadCodec(IndirectionCipher(keychain), PynapliJson)
        val row = codec.encode(payload)
        keychain.stored = null

        val decoded = codec.decode(row)

        assertNull(decoded.payload)
        assertFalse(decoded.needsEncryptionUpgrade)
    }

    /**
     * THE path that closes the plaintext-at-rest window on iOS: an existing plaintext row decrypts to
     * null, the codec parses it raw and flags the upgrade, and re-encoding moves the secret into the
     * side store while the row becomes the sentinel.
     */
    @Test
    fun legacyPlaintextRowUpgradesIntoTheSideStore() {
        val keychain = FakeKeychain()
        val codec = SessionPayloadCodec(IndirectionCipher(keychain), PynapliJson)
        val legacyRow = PynapliJson.encodeToString(StoredSessionPayload.serializer(), payload)

        val decoded = codec.decode(legacyRow)

        assertEquals(payload, decoded.payload)
        assertTrue(decoded.needsEncryptionUpgrade, "plaintext must be flagged for upgrade")

        // What the store does next on that flag.
        val upgradedRow = codec.encode(decoded.payload!!)
        assertEquals(SENTINEL, upgradedRow)
        assertFalse(upgradedRow.contains("access-token-1"))
        assertTrue(keychain.stored.orEmpty().contains("access-token-1"))
    }

    @Test
    fun forgettingTheSecretLeavesNothingRecoverable() {
        val keychain = FakeKeychain()
        val cipher = IndirectionCipher(keychain)
        val codec = SessionPayloadCodec(cipher, PynapliJson)
        val row = codec.encode(payload)

        cipher.forgetStoredSecret()

        assertNull(keychain.stored, "sign-out must not leave the token on the device")
        assertNull(codec.decode(row).payload)
    }

    @Test
    fun inBandCiphersAreUnaffectedByTheForgetHook() {
        // The hook defaults to a no-op precisely so Android/JVM/web keep their current behaviour.
        PassThroughSessionTokenCipher.forgetStoredSecret()
        val codec = SessionPayloadCodec(PassThroughSessionTokenCipher, PynapliJson)

        assertEquals(payload, codec.decode(codec.encode(payload)).payload)
    }

    private companion object {
        const val SENTINEL = "kc1"
    }

    /** Stands in for the Keychain: one slot, independently clearable. */
    private class FakeKeychain {
        var stored: String? = null
    }

    /** Mirrors `KeychainSessionTokenCipher`'s semantics exactly, minus the platform call. */
    private class IndirectionCipher(
        private val keychain: FakeKeychain,
    ) : SessionTokenCipher {
        override fun encrypt(plaintext: String): String {
            keychain.stored = plaintext
            return SENTINEL
        }

        override fun decrypt(value: String): String? = if (value == SENTINEL) keychain.stored else null

        override fun forgetStoredSecret() {
            keychain.stored = null
        }
    }
}
