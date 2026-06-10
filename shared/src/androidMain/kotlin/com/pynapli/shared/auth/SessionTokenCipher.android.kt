package com.pynapli.shared.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

actual fun createPlatformSessionTokenCipher(): SessionTokenCipher = AndroidKeystoreSessionTokenCipher()

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore (available on the
 * whole minSdk 23+ range). Ciphertext format: `enc1:<base64 iv>:<base64 ct>`.
 * [decrypt] returns null on any failure — unknown format, tampered value, or an
 * invalidated key — which the session store surfaces as a signed-out session.
 */
internal class AndroidKeystoreSessionTokenCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SessionTokenCipher {
    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())
        return "$PREFIX${Base64.encode(cipher.iv)}$SEPARATOR${Base64.encode(ciphertext)}"
    }

    override fun decrypt(value: String): String? {
        if (!value.startsWith(PREFIX)) return null
        return runCatching {
            val parts = value.removePrefix(PREFIX).split(SEPARATOR)
            require(parts.size == 2) { "Malformed ciphertext." }
            val iv = Base64.decode(parts[0])
            val ciphertext = Base64.decode(parts[1])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext).decodeToString()
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        existingKey()?.let { return it }
        // Generating twice for the same alias would replace the entry and orphan
        // ciphertext made with the first key, so first use is serialized.
        synchronized(keyCreationLock) {
            existingKey()?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec
                    .Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build(),
            )
            return generator.generateKey()
        }
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    private companion object {
        private val keyCreationLock = Any()
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "pynapli.session.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFIX = "enc1:"
        private const val SEPARATOR = ":"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_BITS = 128
    }
}
