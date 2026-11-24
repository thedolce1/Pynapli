package com.pynapli.app.location

import com.pynapli.shared.core.result.runCatchingCancellable
import com.pynapli.shared.settings.LocationSharingConsentStore

/**
 * Decides whether the silent resume refresh ([refreshInBackground]) may run, from what the member
 * chose in the app rather than what the OS allows. An OS or browser grant is per install, so on its
 * own it would let account A's grant send account B's location, and would keep sending a member who
 * tapped "Skip for now".
 *
 * The silent path runs only when all hold:
 * - someone is signed in;
 * - no sign-up stepper is on screen (the Location step asks; nothing answers it silently, including
 *   a return from Settings: the member taps Share there, which no longer needs a prompt);
 * - this account shared its location from inside the app on this device ([recordSharedInApp]) and
 *   has not skipped since ([recordSkipped]).
 *
 * The shell still checks the OS permission itself, so this never leads to a prompt.
 */
class LocationSharingGate(
    private val store: LocationSharingConsentStore,
    private val currentUserId: () -> String?,
) {
    /** The member shared from the Location step or the Grid, and the fix was accepted. */
    suspend fun recordSharedInApp() {
        val userId = currentUserId() ?: return
        runCatchingCancellable { store.save(store.load().withShared(userId)) }
    }

    /** "Skip for now" on the Location step: this account is not sent silently until it shares. */
    suspend fun recordSkipped() {
        val userId = currentUserId() ?: return
        runCatchingCancellable { store.save(store.load().withoutShared(userId)) }
    }

    suspend fun allowsSilentRefresh(onboardingActive: Boolean): Boolean {
        if (onboardingActive) return false
        val userId = currentUserId() ?: return false
        return runCatchingCancellable { store.load().sharedInApp(userId) }.getOrDefault(false)
    }
}
