package com.pynapli.web

import com.pynapli.app.grid.GridStateHolder
import com.pynapli.app.location.DeviceLocation
import com.pynapli.app.location.LocationSharingGate
import com.pynapli.app.location.LocationStateHolder
import com.pynapli.app.location.refreshInBackground
import com.pynapli.app.location.shareAndRefresh
import com.pynapli.web.platform.geolocationAvailable
import com.pynapli.web.platform.requestBrowserGeolocation
import com.pynapli.web.platform.whenGeolocationAlreadyGranted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Browser Geolocation adapter (WEB_PWA_GAMEPLAN.md W2.3): the web twin of
 * `MainActivity.requestCurrentLocation` — one-shot fix on explicit user action,
 * same `LocationStateHolder` state machine (denied/unavailable copy included),
 * same movement-thresholded grid refresh. The browser shows its own permission
 * prompt; nothing here stores raw coordinates client-side, and the state holder sends only the
 * fix's 0.01-degree cell (no accuracy) to the server.
 */
class WebLocationAdapter(
    private val locationStateHolder: LocationStateHolder,
    private val gridStateHolder: GridStateHolder,
    private val scope: CoroutineScope,
    /** What this account chose in the app; null disables the silent refresh entirely. */
    private val sharingGate: LocationSharingGate?,
    /** True while the sign-up stepper is on screen: its Location step asks, nothing answers it silently. */
    private val onboardingActive: () -> Boolean,
    /** True while a Travel location is set: a grid answered from there says nothing about a shared current location. */
    private val travelActive: () -> Boolean = { false },
) {
    fun requestCurrentLocation() {
        if (!geolocationAvailable()) {
            locationStateHolder.markUnavailable()
            return
        }
        locationStateHolder.beginUpdate()
        requestBrowserGeolocation(
            onSuccess = { latitude, longitude, accuracyMeters ->
                scope.launch {
                    // The member's own share: also what later allows the silent refresh.
                    locationStateHolder.shareAndRefresh(
                        DeviceLocation(
                            latitude = latitude,
                            longitude = longitude,
                            accuracyMeters = accuracyMeters.toInt(),
                        ),
                        gridStateHolder,
                        sharingGate,
                    )
                }
            },
            // A browser remembers a refusal for the site and will not prompt again from a click, so
            // the screen explains where its switch is (LocationSettingsAccess.blockedHint).
            onPermissionDenied = { locationStateHolder.markPermissionDenied(permanently = true) },
            onUnavailable = { locationStateHolder.markUnavailable() },
        )
    }

    /**
     * Tab visible again (or the page just loaded signed in): only for an account that shared its
     * location in the app on this browser, never while the sign-up stepper is up, and only when the
     * site already holds the permission. Then a fresh fix is sent silently, only if its cell changed.
     * Never prompts, never shows progress or errors; a refused or failed fix is simply dropped.
     */
    fun refreshIfAlreadyGranted() {
        if (!geolocationAvailable()) return
        val gate = sharingGate ?: return

        // Only reached when the site already holds the permission, so it is passed as granted.
        suspend fun allowed(): Boolean =
            gate.allowsSilentRefresh(
                onboardingActive = onboardingActive(),
                osPermissionGranted = true,
                serverHasLocation = gridStateHolder.serverHasLocation.value == true && !travelActive(),
            )
        whenGeolocationAlreadyGranted {
            scope.launch {
                if (!allowed()) return@launch
                requestBrowserGeolocation(
                    onSuccess = { latitude, longitude, accuracyMeters ->
                        scope.launch {
                            // Checked again: the fix can take seconds, and a sign-up may have started.
                            if (!allowed()) return@launch
                            locationStateHolder.refreshInBackground(
                                DeviceLocation(
                                    latitude = latitude,
                                    longitude = longitude,
                                    accuracyMeters = accuracyMeters.toInt(),
                                ),
                                gridStateHolder,
                            )
                        }
                    },
                    onPermissionDenied = {},
                    onUnavailable = {},
                )
            }
        }
    }
}
