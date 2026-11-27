package com.pynapli.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.pynapli.app.location.LocationSettingsOpener
import com.pynapli.app.location.LocationSettingsTarget

/**
 * Opens Android's own location screens for a member who blocked location for good or switched
 * location services off. Coming back from them fires `onStart`, whose resume refresh sends a fresh
 * fix when the permission is now granted.
 */
class AndroidLocationSettingsOpener(
    private val activity: Activity,
) : LocationSettingsOpener {
    override fun open(target: LocationSettingsTarget): Boolean =
        when (target) {
            LocationSettingsTarget.AppPermission -> start(appDetailsIntent())
            // Some builds ship no location-source screen; the app page still leads to it.
            LocationSettingsTarget.LocationServices ->
                start(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) || start(appDetailsIntent())
        }

    private fun appDetailsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))

    // ActivityNotFoundException (no such screen on this build) or a SecurityException from an OEM.
    private fun start(intent: Intent): Boolean = runCatching { activity.startActivity(intent) }.isSuccess
}
