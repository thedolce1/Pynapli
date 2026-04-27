package com.pynapli.server.auth.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PasswordHasher(
    private val iterations: Int = 210_000,
    private val keyLengthBits: Int = 256
) {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun hash(password: String): PasswordHash {
        val salt = ByteArray(16)
        random.nextBytes(salt)
        val hash = pbkdf2(password, salt, iterations)
        return PasswordHash(
            hash = encoder.encodeToString(hash),
            salt = encoder.encodeToString(salt),
            iterations = iterations
        )
    }

    fun verify(password: String, stored: PasswordHash): Boolean {
        val salt = decoder.decode(stored.salt)
        val calculated = pbkdf2(password, salt, stored.iterations)
        val storedBytes = decoder.decode(stored.hash)
        return MessageDigest.isEqual(calculated, storedBytes)
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLengthBits)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}

data class PasswordHash(
    val hash: String,
    val salt: String,
    val iterations: Int
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

