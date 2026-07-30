package com.pynapli.app

/**
 * The three Keychain operations the Swift shell supplies to [IosAppGraph].
 *
 * A named type rather than three loose lambdas so Swift's call site reads as a unit and cannot pass
 * them in the wrong order — `read`, `write` and `delete` all have distinct shapes here, but a future
 * change (say, a keyed variant) could easily make two of them interchangeable.
 *
 * Swift day-one sketch — verify each point against Apple's Keychain documentation as you write it,
 * and see `KeychainSessionTokenCipher` for the rationale and the full checklist:
 *
 * ```swift
 * // kSecClassGenericPassword, fixed service + account, AfterFirstUnlockThisDeviceOnly.
 * // SecItemAdd returns errSecDuplicateItem instead of overwriting -> fall back to SecItemUpdate,
 * // or a second sign-in keeps the first session's token.
 * // read must return nil on errSecItemNotFound, never throw.
 * let bridge = IosKeychainBridge(
 *     read:   { KeychainStore.read() },
 *     write:  { value in KeychainStore.write(value) },
 *     delete: { KeychainStore.delete() }
 * )
 * let graph = IosPynapliEntry.shared.createGraph(config: config, installId: id, keychain: bridge)
 * ```
 *
 * Kept in `composeApp` beside [IosAppGraph] rather than in `:shared` because it exists purely to
 * shape the shell's hand-off; the storage contract itself lives in `:shared`.
 */
class IosKeychainBridge(
    /** Returns the stored session payload, or null when no item exists. Must not throw. */
    val read: () -> String?,
    /** Stores [value], replacing any existing item for the same service/account. */
    val write: (String) -> Unit,
    /** Removes the item. Called on sign-out so the token does not outlive the session. */
    val delete: () -> Unit,
)
