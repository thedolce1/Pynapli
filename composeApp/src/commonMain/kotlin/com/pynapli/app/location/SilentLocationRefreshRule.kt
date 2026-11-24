package com.pynapli.app.location

import com.pynapli.app.PynapliAppGraph

/**
 * Whether the silent resume refresh ([refreshInBackground]) may run (GRID_IMPLEMENTATION_SPEC,
 * Location Strategy). Nothing is stored on the device for it; all four inputs are live:
 * - [signedIn]: there is an account to send for.
 * - not [onboardingActive]: the sign-up stepper's Location step asks, and nothing answers it
 *   silently (a return from Settings is answered by the member tapping Share, which no longer
 *   prompts).
 * - [osPermissionGranted]: the OS or browser permission is already granted, so this never prompts.
 * - [serverHasLocation] is true: the last live Grid answer this session was not `LOCATION_REQUIRED`
 *   ([com.pynapli.app.grid.GridStateHolder.serverHasLocation]), so the server already holds a
 *   current location for this account and the refresh only keeps it fresh. A Travel-mode viewer
 *   without a current location is answered `LOCATION_REQUIRED` too (server
 *   `GridTravelOnlyViewerTest`), so Travel needs no exception. Null (no live answer yet: cached
 *   cards, or just signed in) blocks.
 *
 * Consequences: a member who skipped at sign-up has no server location, so another account's OS
 * grant on the same device never sends theirs. Accepted residual: an existing member who signs in
 * on a device that already granted location gets their location refreshed without a new prompt, as
 * on Grindr.
 */
fun silentLocationRefreshAllowed(
    signedIn: Boolean,
    onboardingActive: Boolean,
    osPermissionGranted: Boolean,
    serverHasLocation: Boolean?,
): Boolean = signedIn && !onboardingActive && osPermissionGranted && serverHasLocation == true

/** [silentLocationRefreshAllowed] fed from this graph; the shell supplies only the OS permission. */
fun PynapliAppGraph.silentLocationRefreshAllowed(osPermissionGranted: Boolean): Boolean =
    silentLocationRefreshAllowed(
        signedIn = authStateHolder.state.value.authenticated,
        onboardingActive =
            onboardingStateHolder
                ?.state
                ?.value
                ?.active == true,
        osPermissionGranted = osPermissionGranted,
        serverHasLocation = gridStateHolder.serverHasLocation.value,
    )
