package com.pynapli.app.location

import com.pynapli.shared.core.result.runCatchingCancellable
import com.pynapli.shared.settings.LocationSharingConsent
import com.pynapli.shared.settings.LocationSharingConsentStore

/**
 * Decides whether the silent resume refresh ([refreshInBackground]) may run, from what the member
 * chose rather than what the OS allows. An OS or browser grant is per install, so on its own it
 * would let account A's grant send account B's location, and would keep sending a member who tapped
 * "Skip for now".
 *
 * The silent path runs only when all hold:
 * - someone is signed in;
 * - no sign-up stepper is on screen (the Location step asks; nothing answers it silently, including
 *   a return from Settings: the member taps Share there, which no longer needs a prompt);
 * - the OS or browser permission is already granted (so this never leads to a prompt);
 * - this account has not tapped "Skip for now" since it last shared; and
 * - this account consented, which is either of:
 *   - (a) it shared from inside the app on this device ([recordSharedInApp]); or
 *   - (b) the server already holds a location for it, seen as a live Grid answer that was not
 *     `LOCATION_REQUIRED` ([com.pynapli.app.grid.GridStateHolder.serverHasLocation]). That is how a
 *     member who shared before this record existed, or on another device, or whose in-app share
 *     failed on a flaky network, is covered. A fresh sign-up that skipped has no server location,
 *     so another account's OS grant still never leaks into it. (b) is recorded when it is seen.
 *
 * One record for the current account only; [clearForSignOut] drops it, and the rules above derive
 * it again at the next sign-in.
 */
class LocationSharingGate(
    private val store: LocationSharingConsentStore,
    private val currentUserId: () -> String?,
) {
    /** The member shared from the Location step or the Grid, and the fix was accepted. */
    suspend fun recordSharedInApp() {
        val userId = currentUserId() ?: return
        save(LocationSharingConsent(userId = userId, shared = true, skipped = false))
    }

    /** "Skip for now": this account is not sent silently until it taps Share, whatever (b) says. */
    suspend fun recordSkipped() {
        val userId = currentUserId() ?: return
        save(LocationSharingConsent(userId = userId, shared = false, skipped = true))
    }

    /** Signed out: forget the account's record; the next sign-in derives its own. */
    suspend fun clearForSignOut() {
        save(LocationSharingConsent())
    }

    /**
     * @param osPermissionGranted the platform permission is already granted (checked by the shell).
     * @param serverHasLocation the last live Grid answer this session was not `LOCATION_REQUIRED`.
     */
    suspend fun allowsSilentRefresh(
        onboardingActive: Boolean,
        osPermissionGranted: Boolean,
        serverHasLocation: Boolean,
    ): Boolean {
        if (onboardingActive || !osPermissionGranted) return false
        val userId = currentUserId() ?: return false
        val record = runCatchingCancellable { store.load() }.getOrNull()?.forUser(userId) ?: return false
        if (record.skipped) return false
        if (record.shared) return true
        if (!serverHasLocation) return false
        // Rule (b): consent seen now, recorded now.
        save(record.copy(shared = true))
        return true
    }

    private suspend fun save(consent: LocationSharingConsent) {
        runCatchingCancellable { store.save(consent) }
    }
}
