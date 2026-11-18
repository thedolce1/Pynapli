package com.pynapli.app.profile

import com.pynapli.app.grid.GridCardTone
import com.pynapli.app.grid.GridLinkedPartnerUi
import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ReportTargetType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A confirmed change to the viewer's private Favorites. */
data class FavoriteChange(
    val userId: String,
    val favorite: Boolean,
)

class ProfileDetailStateHolder(
    private val repository: PublicProfileRepository,
) {
    private val _state = MutableStateFlow(ProfileDetailUiState())
    val state: StateFlow<ProfileDetailUiState> = _state.asStateFlow()

    suspend fun open(
        card: GridProfileCardUi,
        flirtSource: String? = null,
        isOwnProfile: Boolean = false,
        likeBackEventId: String? = null,
    ) {
        _state.update {
            ProfileDetailUiState(
                selectedCard = card,
                isLoading = true,
                flirtSource = flirtSource,
                isOwnProfile = isOwnProfile,
                likeBackEventId = likeBackEventId,
            )
        }

        when (val result = repository.loadProfile(card.id)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        profile = result.value,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isLoading = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun openLinkedPartner(
        partner: GridLinkedPartnerUi,
        flirtSource: String? = null,
    ) {
        open(
            GridProfileCardUi(
                id = partner.userId,
                displayName = partner.displayName ?: "Profile ${partner.userId.take(8)}",
                thumbnailUrl = partner.thumbnailUrl,
                online = false,
                distanceLabel = null,
                tone = GridCardTone.entries[partner.userId.stableIndex(GridCardTone.entries.size)],
            ),
            flirtSource = flirtSource,
        )
    }

    /**
     * Opens a profile from the Playing Tonight surface so a subsequent flirt is tagged
     * [PLAYING_TONIGHT_SOURCE]. Normal grid/swipe opens leave [ProfileDetailUiState.flirtSource]
     * null, so a later flirt is never mislabeled.
     */
    suspend fun openFromTonight(userId: String) {
        openLinkedPartner(
            GridLinkedPartnerUi(userId = userId),
            flirtSource = PLAYING_TONIGHT_SOURCE,
        )
    }

    /**
     * Opens the viewer's OWN public profile in read-only preview mode (Hub "Preview profile" row),
     * so they can see exactly what other users see. The sheet hides block/hide/report and all
     * outbound actions.
     */
    suspend fun openOwnPreview(
        userId: String,
        displayName: String?,
        profilePhotoUrl: String?,
    ) {
        open(
            GridProfileCardUi(
                id = userId,
                displayName = displayName?.takeIf { it.isNotBlank() } ?: "Your profile",
                thumbnailUrl = profilePhotoUrl,
                online = false,
                distanceLabel = null,
                tone = GridCardTone.entries[userId.stableIndex(GridCardTone.entries.size)],
            ),
            isOwnProfile = true,
        )
    }

    suspend fun sendSpark() {
        val targetUserId = _state.value.profile?.userId ?: _state.value.selectedCard?.id ?: return
        _state.update { current ->
            current.copy(
                isSendingFlirt = true,
                albumFeedback = null,
                flirtFeedback = null,
                errorMessage = null,
            )
        }

        when (val result = repository.sendFlirt(targetUserId, _state.value.flirtSource)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        isSendingFlirt = false,
                        flirtFeedback = "Spark sent",
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isSendingFlirt = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    /**
     * Stars or un-stars the open profile (private Favorites). Optimistic: the star flips at once and
     * reverts if the server refuses (cap reached, person gone). The other person is never told.
     */
    suspend fun toggleFavorite() {
        val current = _state.value
        val profile = current.profile ?: return
        if (current.isTogglingFavorite || current.isOwnProfile) return
        val target = !profile.favorite
        _state.update { it.copy(profile = it.profile?.copy(favorite = target), isTogglingFavorite = true, errorMessage = null) }
        when (val result = repository.setFavorite(profile.userId, target)) {
            is AppResult.Success -> {
                _state.update { it.copy(isTogglingFavorite = false) }
                _favoriteChanges.tryEmit(FavoriteChange(profile.userId, target))
            }
            is AppResult.Failure ->
                _state.update {
                    it.copy(
                        profile = it.profile?.takeIf { p -> p.userId == profile.userId }?.copy(favorite = !target) ?: it.profile,
                        isTogglingFavorite = false,
                        errorMessage = result.error.message,
                    )
                }
        }
    }

    private val _favoriteChanges = MutableSharedFlow<FavoriteChange>(extraBufferCapacity = 8)

    private val _removedProfiles = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /**
     * People blocked, hidden or reported from any sheet (Grid, Swipe, Activity, Messages, Psync), so
     * lists kept across tabs (the Grid, Favorites) drop them even when the sheet wasn't theirs.
     */
    val removedProfiles: SharedFlow<String> = _removedProfiles.asSharedFlow()

    /** Confirmed star/un-star events, so the Favorites view can add or drop a card without a refetch. */
    val favoriteChanges: SharedFlow<FavoriteChange> = _favoriteChanges.asSharedFlow()

    suspend fun requestAlbumAccess() {
        val targetUserId = _state.value.profile?.userId ?: _state.value.selectedCard?.id ?: return
        _state.update { current ->
            current.copy(
                isRequestingAlbum = true,
                flirtFeedback = null,
                albumFeedback = null,
                errorMessage = null,
            )
        }

        when (val result = repository.requestAlbumAccess(targetUserId)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        isRequestingAlbum = false,
                        profile = current.profile?.copy(privateAlbumAccessStatus = "pending"),
                        albumFeedback = "Album request sent",
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isRequestingAlbum = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun loadPrivateAlbum() {
        val ownerUserId = _state.value.profile?.userId ?: _state.value.selectedCard?.id ?: return
        _state.update { current ->
            current.copy(
                isLoadingPrivateAlbum = true,
                albumFeedback = null,
                errorMessage = null,
            )
        }

        when (val result = repository.loadPrivateAlbum(ownerUserId)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        isLoadingPrivateAlbum = false,
                        privateAlbumItems = result.value,
                        profile = current.profile?.copy(privateAlbumAccessStatus = "granted"),
                        albumFeedback = if (result.value.isEmpty()) "Private album is empty" else null,
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isLoadingPrivateAlbum = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun blockUser(reason: String? = null) {
        val targetUserId = _state.value.profile?.userId ?: _state.value.selectedCard?.id ?: return
        if (_state.value.isBlocking) return

        _state.update { current ->
            current.copy(
                isBlocking = true,
                flirtFeedback = null,
                albumFeedback = null,
                reportFeedback = null,
                errorMessage = null,
            )
        }

        when (val result = repository.blockUser(targetUserId, reason)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        isBlocking = false,
                        blockedTargetUserId = targetUserId,
                    )
                }
                _removedProfiles.tryEmit(targetUserId)
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isBlocking = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun hideProfile() {
        val targetUserId = _state.value.profile?.userId ?: _state.value.selectedCard?.id ?: return
        if (_state.value.isHiding) return

        _state.update { current ->
            current.copy(
                isHiding = true,
                flirtFeedback = null,
                albumFeedback = null,
                reportFeedback = null,
                errorMessage = null,
            )
        }

        when (val result = repository.hideProfile(targetUserId)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        isHiding = false,
                        hiddenTargetUserId = targetUserId,
                    )
                }
                _removedProfiles.tryEmit(targetUserId)
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isHiding = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun submitReport(
        category: String,
        note: String? = null,
    ) {
        val targetUserId = _state.value.profile?.userId ?: _state.value.selectedCard?.id ?: return
        if (_state.value.isReporting) return

        _state.update { current ->
            current.copy(
                isReporting = true,
                flirtFeedback = null,
                albumFeedback = null,
                reportFeedback = null,
                errorMessage = null,
            )
        }

        when (val result = repository.submitReport(targetUserId, ReportTargetType.PROFILE, category, note)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        isReporting = false,
                        reportFeedback = REPORT_CONFIRMATION,
                        reportedTargetUserId = targetUserId,
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isReporting = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    /**
     * Reports a private album the owner shared with this viewer. The server checks the grant and
     * attaches the album's media ids as evidence for moderators. Reporting the album does not hide
     * the person: the viewer may only object to some photos.
     */
    suspend fun submitAlbumReport(
        albumId: String,
        category: String,
    ) {
        val targetUserId = _state.value.profile?.userId ?: _state.value.selectedCard?.id ?: return
        if (_state.value.isReporting) return
        _state.update { it.copy(isReporting = true, reportFeedback = null, errorMessage = null) }
        when (val result = repository.submitReport(targetUserId, ReportTargetType.ALBUM, category, null, targetId = albumId)) {
            is AppResult.Success ->
                _state.update { it.copy(isReporting = false, reportFeedback = ALBUM_REPORT_CONFIRMATION) }
            is AppResult.Failure ->
                _state.update { it.copy(isReporting = false, errorMessage = result.error.message) }
        }
    }

    fun consumeBlockedTargetUserId(): String? {
        val current = _state.value.blockedTargetUserId ?: return null
        _state.update { it.copy(blockedTargetUserId = null) }
        return current
    }

    fun consumeHiddenTargetUserId(): String? {
        val current = _state.value.hiddenTargetUserId ?: return null
        _state.update { it.copy(hiddenTargetUserId = null) }
        return current
    }

    fun dismiss() {
        // Closing after a report hands the reported person to the same "hidden" path the Hide
        // action uses, so every surface drops their card.
        val reported = _state.value.reportedTargetUserId
        _state.value = ProfileDetailUiState(hiddenTargetUserId = reported)
        reported?.let { _removedProfiles.tryEmit(it) }
    }

    companion object {
        const val PLAYING_TONIGHT_SOURCE = "playing_tonight"
        const val ALBUM_REPORT_CONFIRMATION = "Thanks, we'll review these photos."
        const val REPORT_CONFIRMATION = "Thanks, we'll review it. You won't see them in discovery again."
    }
}
