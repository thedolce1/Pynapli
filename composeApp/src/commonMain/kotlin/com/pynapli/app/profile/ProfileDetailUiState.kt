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
    val isHiding: Boolean = false,
    val isReporting: Boolean = false,
    val privateAlbumItems: List<MediaAlbumItemUi> = emptyList(),
    val flirtFeedback: String? = null,
    val albumFeedback: String? = null,
    val reportFeedback: String? = null,
    val blockedTargetUserId: String? = null,
    val hiddenTargetUserId: String? = null,
    val errorMessage: String? = null,
    // Origin of the surface that opened this profile, carried onto a subsequent flirt so the
    // recipient's Activity feed can show a "via Playing Tonight" label. Null for normal opens.
    val flirtSource: String? = null,
    // True when the viewer opened their OWN public profile from the Hub "Preview profile" row:
    // the sheet renders read-only (no block/hide/report, no message/spark/album actions).
    val isOwnProfile: Boolean = false,
) {
    val visible: Boolean
        get() = selectedCard != null
}
