package com.pynapli.app.notifications

import android.content.Context

class AndroidPushRegistrationStore(
    context: Context,
) : PushRegistrationStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun currentSnapshot(): PushRegistrationSnapshot? {
        val fingerprint = preferences.getString(KEY_FINGERPRINT, null)?.takeIf { it.isNotEmpty() } ?: return null
        val platform = preferences.getString(KEY_PLATFORM, null)?.takeIf { it.isNotEmpty() } ?: return null
        val token = preferences.getString(KEY_DEVICE_TOKEN, null)?.takeIf { it.isNotEmpty() } ?: return null
        return PushRegistrationSnapshot(
            fingerprint = fingerprint,
            platform = platform,
            deviceToken = token,
        )
    }

    override fun save(snapshot: PushRegistrationSnapshot) {
        preferences
            .edit()
            .putString(KEY_FINGERPRINT, snapshot.fingerprint)
            .putString(KEY_PLATFORM, snapshot.platform)
            .putString(KEY_DEVICE_TOKEN, snapshot.deviceToken)
            .apply()
    }

    override fun clear() {
        preferences
            .edit()
            .remove(KEY_FINGERPRINT)
            .remove(KEY_PLATFORM)
            .remove(KEY_DEVICE_TOKEN)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "pynapli_push_registration"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_PLATFORM = "platform"
        private const val KEY_DEVICE_TOKEN = "device_token"
    }
}
