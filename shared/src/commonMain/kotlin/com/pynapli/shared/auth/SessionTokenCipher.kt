package com.pynapli.shared.auth

/**
 * Encrypts the persisted auth session at rest (ONBOARDING_AUTH_SPEC "Secure token
 * storage: iOS Keychain / Android Keystore"). The ciphertext format is owned by
 * each implementation; callers only rely on the round-trip contract.
 */
interface SessionTokenCipher {
    /** Encrypts [plaintext] into an opaque self-describing string. */
    fun encrypt(plaintext: String): String

    /**
     * Reverses [encrypt]. Returns null when the value was not produced by this
     * cipher or can no longer be decrypted (e.g. the device key was invalidated) —
     * callers treat that as a signed-out session, never a crash.
     */
    fun decrypt(value: String): String?
}

/**
 * Platform cipher: Android Keystore AES-GCM on Android; identity pass-through on
 * desktop JVM (dev/test target without a device keystore) and on iOS until the
 * Keychain-backed actual lands with the iOS phase.
 */
expect fun createPlatformSessionTokenCipher(): SessionTokenCipher

/** Identity cipher for platforms without a hardware-backed keystore wiring yet. */
object PassThroughSessionTokenCipher : SessionTokenCipher {
    override fun encrypt(plaintext: String): String = plaintext

    override fun decrypt(value: String): String = value
}
