package com.pynapli.app.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.grid.GridLinkedPartnerUi
import com.pynapli.shared.distance.formatDistanceForUnit
import com.pynapli.shared.settings.AppSettings

enum class ProfileDetailActionMode {
    Grid,
    SwipeUnmatched,
    SwipeMatched,
}

data class ProfileLinkActionState(
    val label: String,
    val enabled: Boolean,
    val isSubmitting: Boolean,
    val feedbackMessage: String? = null,
    val errorMessage: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDetailSheet(
    state: ProfileDetailUiState,
    onDismiss: () -> Unit,
    onMessage: () -> Unit,
    onSpark: () -> Unit,
    onRequestAlbum: () -> Unit,
    onViewPrivateAlbum: () -> Unit,
    onHide: () -> Unit,
    onBlock: (reason: String?) -> Unit,
    onReport: (category: String, note: String?) -> Unit,
    actionMode: ProfileDetailActionMode = ProfileDetailActionMode.Grid,
    isSwipeSubmitting: Boolean = false,
    onSwipePass: () -> Unit = {},
    onSwipeLike: () -> Unit = {},
    profileLinkAction: ProfileLinkActionState? = null,
    onInviteProfileLink: () -> Unit = {},
    onOpenLinkedPartner: (GridLinkedPartnerUi) -> Unit = {},
    distanceUnit: String = AppSettings.UNIT_METRIC,
    modifier: Modifier = Modifier,
) {
    val card = state.selectedCard ?: return
    val profile = state.profile
    val displayName = profile?.displayName ?: card.displayName
    val distanceLabel =
        formatDistanceForUnit(
            distanceMeters = profile?.distanceMeters ?: card.distanceMeters,
            fallbackLabel = profile?.distanceLabel ?: card.distanceLabel,
            distanceUnit = distanceUnit,
        )
    val profilePhotoUrl = profile?.profilePhotoUrl ?: card.thumbnailUrl
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showBlockConfirm by remember { mutableStateOf(false) }
    var showHideConfirm by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProfileHero(
                displayName = displayName,
                online = card.online,
                distanceLabel = distanceLabel,
                profilePhotoUrl = profilePhotoUrl,
                onDismiss = onDismiss,
                onHideClick = { showHideConfirm = true },
                onBlockClick = { showBlockConfirm = true },
                onReportClick = { showReportDialog = true },
                isOwnProfile = state.isOwnProfile,
            )

            when {
                state.isLoading -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                state.errorMessage != null -> {
                    Text(
                        text = state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                profile != null -> {
                    ProfileFacts(profileState = state)
                    LinkedPartnerSection(
                        linkedPartner = profile.linkedPartner,
                        cardLinkedPartner = card.linkedPartner,
                        onOpenLinkedPartner = onOpenLinkedPartner,
                    )
                    if (state.isOwnProfile) {
                        OwnProfilePreviewNote()
                    } else {
                        when (actionMode) {
                            ProfileDetailActionMode.Grid -> {
                                ProfileActions(
                                    allowGridMessages = profile.allowGridMessages,
                                    allowFlirts = profile.allowFlirts,
                                    allowAlbumRequests = profile.allowAlbumRequests,
                                    privateAlbumAccessStatus = profile.privateAlbumAccessStatus,
                                    isSendingFlirt = state.isSendingFlirt,
                                    isRequestingAlbum = state.isRequestingAlbum,
                                    isLoadingPrivateAlbum = state.isLoadingPrivateAlbum,
                                    flirtFeedback = state.flirtFeedback,
                                    albumFeedback = state.albumFeedback,
                                    profileLinkAction = profileLinkAction,
                                    onMessage = onMessage,
                                    onSpark = onSpark,
                                    onRequestAlbum = onRequestAlbum,
                                    onViewPrivateAlbum = onViewPrivateAlbum,
                                    onInviteProfileLink = onInviteProfileLink,
                                )
                            }

                            ProfileDetailActionMode.SwipeUnmatched -> {
                                SwipeProfileActions(
                                    isSubmitting = isSwipeSubmitting,
                                    onPass = onSwipePass,
                                    onLike = onSwipeLike,
                                )
                            }

                            ProfileDetailActionMode.SwipeMatched -> {
                                MatchedSwipeProfileActions(onMessage = onMessage)
                            }
                        }
                    }
                    state.reportFeedback?.let { feedback ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = feedback,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    PrivateAlbumPreview(
                        items = state.privateAlbumItems,
                        isLoading = state.isLoadingPrivateAlbum,
                    )
                }
            }
        }
    }

    if (showBlockConfirm) {
        BlockConfirmDialog(
            displayName = displayName,
            isBlocking = state.isBlocking,
            onConfirm = { reason ->
                showBlockConfirm = false
                onBlock(reason)
            },
            onDismiss = { showBlockConfirm = false },
        )
    }

    if (showHideConfirm) {
        HideProfileConfirmDialog(
            displayName = displayName,
            isHiding = state.isHiding,
            onConfirm = {
                showHideConfirm = false
                onHide()
            },
            onDismiss = { showHideConfirm = false },
        )
    }

    if (showReportDialog) {
        ReportCategoryDialog(
            isSubmitting = state.isReporting,
            onSubmit = { category, note ->
                showReportDialog = false
                onReport(category, note)
            },
            onDismiss = { showReportDialog = false },
        )
    }
}

/** Read-only footer shown when a viewer previews their OWN public profile from the Hub. */
@Composable
private fun OwnProfilePreviewNote() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Visibility,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "This is how your profile looks to others. Tap close when you're done.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}
