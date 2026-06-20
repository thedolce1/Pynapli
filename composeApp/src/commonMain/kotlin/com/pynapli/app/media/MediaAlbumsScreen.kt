package com.pynapli.app.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.profilelink.ProfileLinksStateHolder
import com.pynapli.app.profilelink.ProfileLinksUiState
import kotlinx.coroutines.launch

@Composable
fun MediaAlbumsScreen(
    stateHolder: MediaAlbumsStateHolder,
    profileLinksStateHolder: ProfileLinksStateHolder,
    onBack: () -> Unit,
    onPickMedia: (MediaAlbumKindUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by stateHolder.state.collectAsState()
    val profileLinksState by profileLinksStateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<MediaAlbumItemUi?>(null) }

    LaunchedEffect(stateHolder) {
        stateHolder.load()
    }

    LaunchedEffect(profileLinksStateHolder) {
        profileLinksStateHolder.load()
    }

    MediaAlbumsContent(
        state = state,
        profileLinksState = profileLinksState,
        onBack = onBack,
        onAddProfile = { onPickMedia(MediaAlbumKindUi.Profile) },
        onAddPrivate = { onPickMedia(MediaAlbumKindUi.Private) },
        onRetryUpload = { kind ->
            scope.launch { stateHolder.retryUpload(kind) }
        },
        onRequestDelete = { item -> pendingDelete = item },
        onPreviewReorder = { kind, fromAssetId, toAssetId ->
            stateHolder.previewReorder(kind, fromAssetId, toAssetId)
        },
        onCommitReorder = { kind ->
            scope.launch { stateHolder.commitReorder(kind) }
        },
        onSetAsMain = { item ->
            scope.launch { stateHolder.setAsMain(item.mediaAssetId) }
        },
        onRevokeGrant = { granteeUserId ->
            scope.launch { stateHolder.revokeGrant(granteeUserId) }
        },
        onAcceptProfileLink = { linkId ->
            scope.launch { profileLinksStateHolder.acceptInvitation(linkId) }
        },
        onDeclineProfileLink = { linkId ->
            scope.launch { profileLinksStateHolder.declineInvitation(linkId) }
        },
        onUnlinkProfile = {
            scope.launch { profileLinksStateHolder.unlinkCurrent() }
        },
        onClearError = stateHolder::clearError,
        onClearProfileLinkMessage = profileLinksStateHolder::clearMessages,
        modifier = modifier,
    )

    pendingDelete?.let { item ->
        DeleteMediaDialog(
            item = item,
            onConfirm = {
                pendingDelete = null
                scope.launch { stateHolder.deleteMedia(item.albumKind, item.mediaAssetId) }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun DeleteMediaDialog(
    item: MediaAlbumItemUi,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Delete this photo?") },
        text = {
            Text(
                text =
                    if (item.albumKind == MediaAlbumKindUi.Private) {
                        "It will be removed from your private album for everyone you've shared it with."
                    } else {
                        "It will be removed from your profile."
                    },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        },
    )
}

@Composable
private fun MediaAlbumsContent(
    state: MediaAlbumsUiState,
    profileLinksState: ProfileLinksUiState,
    onBack: () -> Unit,
    onAddProfile: () -> Unit,
    onAddPrivate: () -> Unit,
    onRetryUpload: (MediaAlbumKindUi) -> Unit,
    onRequestDelete: (MediaAlbumItemUi) -> Unit,
    onPreviewReorder: (MediaAlbumKindUi, String, String) -> Unit,
    onCommitReorder: (MediaAlbumKindUi) -> Unit,
    onSetAsMain: (MediaAlbumItemUi) -> Unit,
    onRevokeGrant: (String) -> Unit,
    onAcceptProfileLink: (String) -> Unit,
    onDeclineProfileLink: (String) -> Unit,
    onUnlinkProfile: () -> Unit,
    onClearError: () -> Unit,
    onClearProfileLinkMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val reorderState =
        rememberAlbumReorderState(
            gridState = gridState,
            onMove = onPreviewReorder,
            onDrop = onCommitReorder,
        )
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .albumReorderable(reorderState),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            MediaAlbumsHeader(onBack = onBack)
        }

        state.errorMessage?.let { message ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                ErrorBand(
                    message = message,
                    onDismiss = onClearError,
                )
            }
        }

        profileLinksSection(
            state = profileLinksState,
            onAccept = onAcceptProfileLink,
            onDecline = onDeclineProfileLink,
            onUnlink = onUnlinkProfile,
            onClearMessage = onClearProfileLinkMessage,
        )

        if (state.isLoading && state.profileItems.isEmpty() && state.privateItems.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LoadingAlbumRow()
            }
        }

        albumSection(
            kind = MediaAlbumKindUi.Profile,
            items = state.profileItems,
            isUploading = state.isUploadingProfile,
            canAdd = state.canAddMore(MediaAlbumKindUi.Profile),
            capCountLabel = state.capCountLabel(MediaAlbumKindUi.Profile),
            busyAssetIds = state.busyAssetIds,
            canRetryUpload = MediaAlbumKindUi.Profile in state.retryableUploadKinds,
            reorderState = reorderState,
            onAdd = onAddProfile,
            onRetryUpload = { onRetryUpload(MediaAlbumKindUi.Profile) },
            onRequestDelete = onRequestDelete,
            onSetAsMain = onSetAsMain,
        )

        albumSection(
            kind = MediaAlbumKindUi.Private,
            items = state.privateItems,
            isUploading = state.isUploadingPrivate,
            canAdd = state.canAddMore(MediaAlbumKindUi.Private),
            capCountLabel = state.capCountLabel(MediaAlbumKindUi.Private),
            busyAssetIds = state.busyAssetIds,
            canRetryUpload = MediaAlbumKindUi.Private in state.retryableUploadKinds,
            reorderState = reorderState,
            onAdd = onAddPrivate,
            onRetryUpload = { onRetryUpload(MediaAlbumKindUi.Private) },
            onRequestDelete = onRequestDelete,
            onSetAsMain = onSetAsMain,
        )

        privateGrantsSection(
            grants = state.privateGrants,
            revokingGranteeIds = state.revokingGranteeIds,
            onRevokeGrant = onRevokeGrant,
        )
    }
}
