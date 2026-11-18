package com.pynapli.server.auth.security

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals the tokens a refresh just issued under a key only the holder of the *consumed* refresh token
 * can derive, so a retried refresh (the first response was lost) can be answered with the same
 * tokens without the server ever storing them in a usable form.
 *
 * The key is SHA-256 over a domain-separated copy of the presented refresh token. That is an
 * adequate KDF here because refresh tokens are 256 random bits from [TokenGenerator]; the domain
 * prefix keeps the key distinct from the lookup hash ([Sha256.hash] of the bare token) stored on the
 * row. AES-256-GCM with a fresh 96-bit nonce per seal; the session id is bound as associated data so
 * a blob only opens on the row it was written for. Nothing here logs.
 */
internal object RefreshReplaySeal {
    private const val KEY_DOMAIN = "pynapli-refresh-replay-v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    // Tokens are base64url (A-Z a-z 0-9 - _), so '.' can never occur inside one.
    private const val SEPARATOR = '.'

    private val random = SecureRandom()

    fun seal(
        presentedRefreshToken: String,
        sessionId: UUID,
        accessToken: String,
        refreshToken: String,
    ): SealedRefreshReplay {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(presentedRefreshToken), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData(sessionId))
        val ciphertext = cipher.doFinal("$accessToken$SEPARATOR$refreshToken".toByteArray(Charsets.UTF_8))
        return SealedRefreshReplay(nonce = nonce, ciphertext = ciphertext)
    }

    /** The sealed (access, refresh) tokens, or null when the token, session or blob does not match. */
    fun open(
        presentedRefreshToken: String,
        sessionId: UUID,
        nonce: ByteArray,
        ciphertext: ByteArray,
    ): Pair<String, String>? {
        if (nonce.size != NONCE_BYTES) return null
        val plaintext =
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key(presentedRefreshToken), GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(associatedData(sessionId))
                cipher.doFinal(ciphertext)
            } catch (_: GeneralSecurityException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        val parts = plaintext.toString(Charsets.UTF_8).split(SEPARATOR)
        if (parts.size != 2 || parts.any { it.isEmpty() }) return null
        return parts[0] to parts[1]
    }

    private fun key(presentedRefreshToken: String): SecretKeySpec {
        val material = (KEY_DOMAIN + presentedRefreshToken).toByteArray(Charsets.UTF_8)
        return SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(material), "AES")
    }

    private fun associatedData(sessionId: UUID): ByteArray = "session:$sessionId".toByteArray(Charsets.UTF_8)
}

/** A sealed refresh response: AES-GCM [ciphertext] (with its tag) and the [nonce] it was sealed under. */
internal class SealedRefreshReplay(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)
