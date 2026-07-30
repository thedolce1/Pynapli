package com.pynapli.shared.auth

/**
 * Default iOS cipher: **pass-through, i.e. tokens are plaintext at rest.**
 *
 * Deliberately NOT the Keychain implementation. The Keychain is platform code, and the honest place
 * for it is the Swift shell — see [KeychainSessionTokenCipher], which the shell wires into
 * `IosAppGraph`. This default exists only because `createPlatformSessionTokenCipher()` is a
 * no-argument `expect` and therefore cannot receive the shell's hooks.
 *
 * An earlier attempt implemented the Keychain directly here over CoreFoundation from Kotlin/Native.
 * It was abandoned on purpose: bridging `NSMutableDictionary`/`CFDictionaryRef` and the `kSec*`
 * `CFStringRef` constants is fiddly interop that cannot be executed on a non-Mac host, so the result
 * would have been unverifiable code in the one place where "it compiles" is worth least — secret
 * storage. Twenty lines of idiomatic Swift, written where they can actually be compiled and run,
 * beat a hundred lines of blind interop.
 *
 * The security gap therefore stays OPEN and documented until the shell supplies the hooks; see
 * `docs/IOS_BRINGUP_PLAN.md` step 3.
 */
actual fun createPlatformSessionTokenCipher(): SessionTokenCipher = PassThroughSessionTokenCipher

/**
 * Keychain-backed session storage whose three platform operations are supplied by the Swift shell.
 *
 * The token goes **into the Keychain** and the database row keeps only the sentinel
 * [KEYCHAIN_REFERENCE]. That is deliberately different from Android, which holds a non-exportable AES
 * key in the Keystore and the ciphertext in the row: on iOS the Keychain already *is* the
 * hardware-protected secret store, so encrypting a blob ourselves and then storing that blob would
 * add key material we have to manage for no gain. Here no secret reaches SQLite at all.
 *
 * How this satisfies [SessionTokenCipher] — the interface owns only the round-trip, and states the
 * "ciphertext" format belongs to the implementation:
 *  - [encrypt] writes the payload to the Keychain and returns the sentinel.
 *  - [decrypt] returns the stored payload for the sentinel and **null for anything else**. That null
 *    is what drives `SessionPayloadCodec`'s legacy path: a pre-encryption plaintext row decrypts to
 *    null, the codec parses the raw row and flags an upgrade, which immediately rewrites it through
 *    here — closing the plaintext-at-rest window on the first read after upgrade.
 *  - A missing Keychain item (keychain cleared, device restored from backup) also yields null, and
 *    the sentinel is not valid JSON, so the user lands signed out rather than crashing.
 *  - [forgetStoredSecret] deletes the item on sign-out. Without it, clearing the session would drop
 *    the reference and leave the token behind on the device.
 *
 * What the Swift side must do (and what to check when verifying it on a Mac):
 *  - `kSecClassGenericPassword`, with a fixed `kSecAttrService` + `kSecAttrAccount`.
 *  - `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` — Apple's guidance for auth tokens: readable
 *    by background work after first unlock, unreadable on a device not unlocked since boot, never
 *    synced to iCloud and never migrated to another device.
 *  - `SecItemAdd` returns `errSecDuplicateItem` instead of overwriting, so a duplicate must fall back
 *    to `SecItemUpdate`; otherwise a second sign-in silently keeps the first session's token.
 *  - [read] must return null on `errSecItemNotFound` rather than throwing.
 */
class KeychainSessionTokenCipher(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val delete: () -> Unit,
) : SessionTokenCipher {
    override fun encrypt(plaintext: String): String {
        write(plaintext)
        return KEYCHAIN_REFERENCE
    }

    override fun decrypt(value: String): String? {
        // Anything that is not our sentinel is not ours — including legacy plaintext rows, which the
        // codec then parses and upgrades.
        if (value != KEYCHAIN_REFERENCE) return null
        return read()
    }

    override fun forgetStoredSecret() {
        delete()
    }

    companion object {
        /**
         * Stored in the database row in place of the token. Versioned so a later format change can be
         * told apart from this one rather than mistaken for a legacy plaintext row.
         */
        const val KEYCHAIN_REFERENCE: String = "kc1"

        /** Suggested Keychain coordinates, so Swift and this file cannot disagree about the slot. */
        const val KEYCHAIN_SERVICE: String = "com.pynapli.app.session"
        const val KEYCHAIN_ACCOUNT: String = "auth.session.v1"
    }
}
