package com.pynapli.app.location

import androidx.compose.runtime.Immutable
import com.pynapli.shared.network.dto.CurrentLocationDto

@Immutable
data class LocationUiState(
    val isUpdating: Boolean = false,
    val currentLocation: CurrentLocationDto? = null,
    val errorMessage: String? = null,
    /** Set when asking again cannot help and the member has to change a setting first. */
    val blockedReason: LocationBlockedReason? = null,
)

/** Why location cannot be asked for from inside the app right now. */
enum class LocationBlockedReason {
    /** The permission is denied in a way the OS or browser will not prompt for again. */
    PermissionBlocked,

    /** Location services are off for the whole device. */
    ServicesOff,
}
