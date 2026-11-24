package com.pynapli.app.location

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.handCursor

/** The OS screen that turns location back on. */
enum class LocationSettingsTarget {
    /** This app's own permission page (Android app details). */
    AppPermission,

    /** The device-wide location services switch. */
    LocationServices,
}

/**
 * Opens OS settings for location. Implemented by a shell that has such screens (Android); the shared
 * UI only knows this interface, so no OS call leaves the platform module.
 */
fun interface LocationSettingsOpener {
    /** Opens the screen for [target]; false when the device has nowhere to send the member. */
    fun open(target: LocationSettingsTarget): Boolean
}

/**
 * How this platform lets a member undo a blocked location: an [opener] where the OS has a settings
 * screen to open (Android), or a [blockedHint] that explains where the switch is (a browser cannot
 * open its own site settings). Both null: nothing extra is shown (desktop, iOS until it is wired).
 */
@Immutable
data class LocationSettingsAccess(
    val opener: LocationSettingsOpener? = null,
    val blockedHint: String? = null,
)

/** Provided once by each shell around `PynapliApp`, read by [LocationAccessHelp]. */
val LocalLocationSettingsAccess = staticCompositionLocalOf { LocationSettingsAccess() }

/** Wraps a shell's `PynapliApp` so the Location step and the Grid know how to reach Settings. */
@Composable
fun ProvideLocationSettingsAccess(
    access: LocationSettingsAccess,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalLocationSettingsAccess provides access, content = content)
}

/** Neutral, one line: what not sharing location costs. Shown next to Skip and on the empty Grid. */
const val LOCATION_SKIP_COST_COPY = "Without your location, you won't appear to anyone nearby."

/**
 * The way back when location is blocked: "Open Settings" where the platform can open it, otherwise
 * the platform's hint. Draws nothing when location is not blocked (a plain denial can still be asked
 * again with the screen's own share button). Used by the Location step and the Grid's empty state.
 */
@Composable
fun LocationAccessHelp(
    locationState: LocationUiState,
    modifier: Modifier = Modifier,
) {
    val reason = locationState.blockedReason ?: return
    val access = LocalLocationSettingsAccess.current
    val opener = access.opener
    when {
        opener != null -> {
            val target =
                when (reason) {
                    LocationBlockedReason.PermissionBlocked -> LocationSettingsTarget.AppPermission
                    LocationBlockedReason.ServicesOff -> LocationSettingsTarget.LocationServices
                }
            TextButton(
                onClick = { opener.open(target) },
                modifier = modifier.handCursor(),
            ) {
                Text("Open Settings")
            }
        }

        access.blockedHint != null ->
            Text(
                text = access.blockedHint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = modifier.padding(top = 8.dp),
            )
    }
}
