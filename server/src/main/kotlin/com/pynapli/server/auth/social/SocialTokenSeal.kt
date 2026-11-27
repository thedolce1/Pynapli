package com.pynapli.server.auth.social

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.ProviderException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * At-rest encryption of provider refresh tokens (Apple's, kept only to revoke the grant on deletion or
 * unlink). AES-256-GCM, key = SHA-256 over a purpose label and PYNAPLI_SOCIAL_TOKEN_KEY (a strong
 * server secret, so a plain hash is an adequate KDF), a fresh 96-bit nonce per seal, and the identity
 * row's id as associated data so a sealed token opens only on the row it was written for.
 * Output: nonce || ciphertext+tag. Nothing here logs.
 */
class SocialTokenSeal(
    secret: String,
) {
    private val key: SecretKeySpec =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("$KEY_DOMAIN$secret".toByteArray(Charsets.UTF_8)), "AES")

    fun seal(
        identityId: UUID,
        token: String,
    ): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData(identityId))
        return nonce + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
    }

    /** The token, or null when the blob is not one this key sealed for [identityId]. */
    fun open(
        identityId: UUID,
        sealed: ByteArray,
    ): String? {
        if (sealed.size <= NONCE_BYTES + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, NONCE_BYTES)))
            cipher.updateAAD(associatedData(identityId))
            cipher.doFinal(sealed.copyOfRange(NONCE_BYTES, sealed.size)).toString(Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ProviderException) {
            null
        }
    }

    private fun associatedData(identityId: UUID): ByteArray = "auth-identity:$identityId".toByteArray(Charsets.UTF_8)

    private companion object {
        const val KEY_DOMAIN = "pynapli-social-refresh-token-v1:"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        val random = SecureRandom()
    }
}
