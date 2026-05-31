package com.pynapli.app.location

import androidx.compose.runtime.Immutable
import com.pynapli.shared.network.dto.CurrentLocationDto

@Immutable
data class LocationUiState(
    val isUpdating: Boolean = false,
    val currentLocation: CurrentLocationDto? = null,
    val errorMessage: String? = null,
)
