package com.pynapli.app.profile

import androidx.compose.runtime.Immutable
import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.app.media.MediaAlbumItemUi
import com.pynapli.shared.network.dto.PublicProfileDto

@Immutable
data class ProfileDetailUiState(
    val selectedCard: GridProfileCardUi? = null,
    val profile: PublicProfileDto? = null,
    val isLoading: Boolean = false,
    val isSendingFlirt: Boolean = false,
    val isRequestingAlbum: Boolean = false,
    val isLoadingPrivateAlbum: Boolean = false,
    val isBlocking: Boolean = false,
    val isReporting: Boolean = false,
    val privateAlbumItems: List<MediaAlbumItemUi> = emptyList(),
    val flirtFeedback: String? = null,
    val albumFeedback: String? = null,
    val reportFeedback: String? = null,
    val blockedTargetUserId: String? = null,
    val errorMessage: String? = null
) {
    val visible: Boolean
        get() = selectedCard != null
}
