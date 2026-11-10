package com.pynapli.app.notifications

import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.shared.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** True when the user wants at least one kind of push (messages, matches, activity). */
val AppSettings.anyPushCategoryEnabled: Boolean
    get() = messageNotifications || matchNotifications || activityNotifications

/**
 * When the device's push registration must follow the user: on sign-in/out, on every notification
 * setting change, and on demand (each app foreground, after the OS permission dialog). The platform
 * shell owns only the OS pieces: the token provider inside [coordinator] and the permission dialog.
 * Previously this lived in Android's MainActivity, which iOS would have had to copy.
 */
class PushRegistrationSync(
    private val coordinator: PushRegistrationCoordinator,
    private val authStateHolder: AuthStateHolder,
    private val appSettingsStateHolder: AppSettingsStateHolder,
) {
    /** Re-registers whenever the signed-in user or the settings change. Runs until cancelled. */
    suspend fun followSessionAndSettings() {
        combine(authStateHolder.state, appSettingsStateHolder.state) { auth, settings ->
            auth.session?.userId to settings.settings
        }.distinctUntilChanged()
            .collectLatest { (userId, settings) -> coordinator.syncAuthenticatedDevice(userId, settings) }
    }

    /**
     * One sync with the current state. Call on every foreground: a rotated FCM token clears the stored
     * fingerprint but changes neither the user nor the settings, so [followSessionAndSettings] alone
     * would leave the server holding a dead token. Fingerprint-guarded, so a no-op when nothing changed.
     */
    suspend fun syncNow() {
        coordinator.syncAuthenticatedDevice(
            userId =
                authStateHolder.state.value.session
                    ?.userId,
            settings = appSettingsStateHolder.state.value.settings,
        )
    }

    /**
     * True while the user is signed in with at least one push category on: the moment it's worth asking
     * the OS for notification permission. The shell decides whether its OS needs a prompt at all.
     */
    val notificationPermissionWanted: Flow<Boolean> =
        combine(authStateHolder.state, appSettingsStateHolder.state) { auth, settings ->
            auth.authenticated && settings.settings.anyPushCategoryEnabled
        }.distinctUntilChanged()
}
