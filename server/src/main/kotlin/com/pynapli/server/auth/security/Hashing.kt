package com.pynapli.server.auth.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256. Every stored hash records its own iteration count; [iterations] is the
 * current policy, used for new hashes and for [decoyHash]. Open only so tests can count checks.
 */
open class PasswordHasher(
    val iterations: Int = 210_000,
    private val keyLengthBits: Int = 256,
) {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /**
     * Checked against when a sign-in names no account, so that attempt costs one full PBKDF2 at the
     * current parameters, the same as a wrong password on a real account: the response time can't
     * tell anyone whether an address is a member. Random bytes, not the hash of anything, so no
     * password can match it.
     */
    val decoyHash: PasswordHash =
        PasswordHash(
            hash = encoder.encodeToString(randomBytes(keyLengthBits / Byte.SIZE_BITS)),
            salt = encoder.encodeToString(randomBytes(SALT_BYTES)),
            iterations = iterations,
        )

    fun hash(password: String): PasswordHash {
        val salt = ByteArray(SALT_BYTES)
        random.nextBytes(salt)
        val hash = pbkdf2(password, salt, iterations)
        return PasswordHash(
            hash = encoder.encodeToString(hash),
            salt = encoder.encodeToString(salt),
            iterations = iterations,
        )
    }

    open fun verify(
        password: String,
        stored: PasswordHash,
    ): Boolean {
        val salt = decoder.decode(stored.salt)
        val calculated = pbkdf2(password, salt, stored.iterations)
        val storedBytes = decoder.decode(stored.hash)
        return MessageDigest.isEqual(calculated, storedBytes)
    }

    private fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return bytes
    }

    private fun pbkdf2(
        password: String,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLengthBits)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private companion object {
        const val SALT_BYTES = 16
    }
}

data class PasswordHash(
    val hash: String,
    val salt: String,
    val iterations: Int,
)

object TokenGenerator {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }
}

object Sha256 {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return encoder.encodeToString(digest)
    }
}

fun normalizeEmail(email: String): String = email.trim().lowercase()
