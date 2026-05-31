package com.pynapli.app.notifications

import android.content.Context
import android.provider.Settings
import java.util.UUID

class AndroidDevPushTokenProvider(
    context: Context,
) : PushTokenProvider {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun currentToken(): String {
        val stableId =
            Settings.Secure
                .getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "9774d56d682e549c" }
                ?: preferences.getString(KEY_FALLBACK_ID, null)
                ?: UUID.randomUUID().toString().also { generated ->
                    preferences.edit().putString(KEY_FALLBACK_ID, generated).apply()
                }

        return "pynapli-local-dev-${appContext.packageName}-$stableId"
    }

    companion object {
        private const val PREFERENCES_NAME = "pynapli_push_registration"
        private const val KEY_FALLBACK_ID = "fallback_device_id"
    }
}
