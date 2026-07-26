package com.pynapli.app.notifications

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * Stand-in push token for builds with no working FCM registration — local dev without
 * google-services.json, and the release-path fallback in [AndroidFirebasePushTokenProvider] when
 * Firebase returns no token. The server treats it as an ordinary opaque device token, so pushes
 * simply never arrive for that device rather than the registration failing outright.
 *
 * Deliberately NOT derived from a hardware identifier. This runs in release builds, and
 * Settings.Secure.ANDROID_ID is a cross-app, survives-reinstall device ID that Google Play policy
 * discourages and Play Data Safety would have to declare. A random UUID persisted in app-private
 * preferences gives the same stability per install with none of that — the same trade-off already
 * made and documented for [com.pynapli.app.stableInstallId].
 */
class AndroidDevPushTokenProvider(
    context: Context,
) : PushTokenProvider {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun currentToken(): String {
        val stableId =
            preferences.getString(KEY_FALLBACK_ID, null)?.takeIf { it.isNotEmpty() }
                ?: UUID.randomUUID().toString().also { generated ->
                    preferences.edit { putString(KEY_FALLBACK_ID, generated) }
                }

        return "pynapli-local-dev-${appContext.packageName}-$stableId"
    }

    companion object {
        private const val PREFERENCES_NAME = "pynapli_push_registration"
        private const val KEY_FALLBACK_ID = "fallback_device_id"
    }
}
