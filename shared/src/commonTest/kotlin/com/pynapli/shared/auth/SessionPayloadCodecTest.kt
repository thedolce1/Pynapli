package com.pynapli.shared.auth

import com.pynapli.shared.network.PynapliJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionPayloadCodecTest {
    private val payload =
        StoredSessionPayload(
            accessToken = "access-token-1",
            refreshToken = "refresh-token-1",
            userId = "user-1",
            userStatus = "active",
        )

    @Test
    fun encodedValueCarriesNoPlaintextTokens() {
        val codec = SessionPayloadCodec(FakeCipher(), PynapliJson)

        val value = codec.encode(payload)

        assertFalse(value.contains("access-token-1"))
        assertFalse(value.contains("refresh-token-1"))
    }

    @Test
    fun encryptedValueRoundTrips() {
        val codec = SessionPayloadCodec(FakeCipher(), PynapliJson)

        val decoded = codec.decode(codec.encode(payload))

        assertEquals(payload, decoded.payload)
        assertFalse(decoded.needsEncryptionUpgrade)
    }

    @Test
    fun legacyPlaintextRowDecodesAndRequestsUpgrade() {
        val codec = SessionPayloadCodec(FakeCipher(), PynapliJson)
        val legacyValue = PynapliJson.encodeToString(StoredSessionPayload.serializer(), payload)

        val decoded = codec.decode(legacyValue)

        assertEquals(payload, decoded.payload)
        assertTrue(decoded.needsEncryptionUpgrade)
    }

    @Test
    fun undecryptableValueDecodesToNull() {
        val codec = SessionPayloadCodec(FakeCipher(), PynapliJson)

        // Encrypted-looking value the cipher can no longer decrypt (e.g. the device
        // key was invalidated): not legacy plaintext either, so the session is gone.
        val decoded = codec.decode("fake:not-actually-decryptable-garbage")

        assertNull(decoded.payload)
        assertFalse(decoded.needsEncryptionUpgrade)
    }

    @Test
    fun decryptedGarbageDecodesToNull() {
        val codec = SessionPayloadCodec(GarbageCipher, PynapliJson)

        val decoded = codec.decode("anything")

        assertNull(decoded.payload)
    }

    @Test
    fun passThroughCipherKeepsLegacyBehavior() {
        val codec = SessionPayloadCodec(PassThroughSessionTokenCipher, PynapliJson)

        val decoded = codec.decode(codec.encode(payload))

        assertEquals(payload, decoded.payload)
        // Pass-through decrypt succeeds, so no upgrade loop on platforms without
        // a keystore-backed cipher.
        assertFalse(decoded.needsEncryptionUpgrade)
    }

    /** Reversible toy cipher mirroring the real contract: prefixed, refuses foreign values. */
    private class FakeCipher : SessionTokenCipher {
        override fun encrypt(plaintext: String): String = "fake:" + plaintext.reversed()

        override fun decrypt(value: String): String? =
            if (value.startsWith("fake:")) {
                value.removePrefix("fake:").reversed()
            } else {
                null
            }
    }

    /** Decrypts everything into non-JSON garbage (tampering / wrong key). */
    private object GarbageCipher : SessionTokenCipher {
        override fun encrypt(plaintext: String): String = plaintext

        override fun decrypt(value: String): String = "garbage-not-json"
    }
}
