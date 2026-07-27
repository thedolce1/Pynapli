package com.pynapli.app.notifications

import platform.Foundation.NSUserDefaults
import kotlin.concurrent.AtomicReference

/**
 * APNs token holder. Swift sets this from
 * `application(_:didRegisterForRemoteNotificationsWithDeviceToken:)`; the shared
 * [PushRegistrationCoordinator] reads it exactly as it reads FCM's token on Android.
 *
 * Returns null until the token arrives, which the coordinator already treats as "not registerable
 * yet" rather than an error — registration re-syncs on the next foreground, the same behaviour
 * Android relies on when an FCM token rotates.
 *
 * STATUS: type-checked on Windows, never run. See docs/IOS_BRINGUP_PLAN.md.
 */
class IosPushTokenProvider : PushTokenProvider {
    /**
     * Atomic, not a plain `var`: Swift writes this from an APNs delegate callback while the
     * registration coroutine reads it, and on Kotlin/Native that is a genuine data race rather than
     * a theoretical one. Android has no equivalent because it asks FCM for the token on each call
     * instead of caching a mutable field.
     */
    private val token = AtomicReference<String?>(null)

    /** Called from Swift with the hex-encoded APNs device token. */
    fun updateToken(deviceToken: String?) {
        token.value = deviceToken?.trim()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun currentToken(): String? = token.value
}

/**
 * Registration snapshot persisted in `NSUserDefaults`.
 *
 * Deliberately NOT the Keychain: this is a device token plus a fingerprint, not a secret, and it
 * should be cleared by an app uninstall rather than surviving it the way Keychain items do. Android
 * stores the same thing in SharedPreferences for the same reason.
 */
class IosPushRegistrationStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : PushRegistrationStore {
    override fun currentSnapshot(): PushRegistrationSnapshot? {
        val fingerprint = defaults.stringForKey(KEY_FINGERPRINT)?.takeIf { it.isNotEmpty() } ?: return null
        val platform = defaults.stringForKey(KEY_PLATFORM)?.takeIf { it.isNotEmpty() } ?: return null
        val token = defaults.stringForKey(KEY_DEVICE_TOKEN)?.takeIf { it.isNotEmpty() } ?: return null
        return PushRegistrationSnapshot(fingerprint = fingerprint, platform = platform, deviceToken = token)
    }

    override fun save(snapshot: PushRegistrationSnapshot) {
        defaults.setObject(snapshot.fingerprint, KEY_FINGERPRINT)
        defaults.setObject(snapshot.platform, KEY_PLATFORM)
        defaults.setObject(snapshot.deviceToken, KEY_DEVICE_TOKEN)
    }

    override fun clear() {
        defaults.removeObjectForKey(KEY_FINGERPRINT)
        defaults.removeObjectForKey(KEY_PLATFORM)
        defaults.removeObjectForKey(KEY_DEVICE_TOKEN)
    }

    private companion object {
        const val KEY_FINGERPRINT = "pynapli.push.fingerprint"
        const val KEY_PLATFORM = "pynapli.push.platform"
        const val KEY_DEVICE_TOKEN = "pynapli.push.device_token"
    }
}
