package com.pynapli.app.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
}

@Composable
private fun MediaAlbumsContent(
    state: MediaAlbumsUiState,
    profileLinksState: ProfileLinksUiState,
    onBack: () -> Unit,
    onAddProfile: () -> Unit,
    onAddPrivate: () -> Unit,
    onRevokeGrant: (String) -> Unit,
    onAcceptProfileLink: (String) -> Unit,
    onDeclineProfileLink: (String) -> Unit,
    onUnlinkProfile: () -> Unit,
    onClearError: () -> Unit,
    onClearProfileLinkMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
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
            onAdd = onAddProfile,
        )

        albumSection(
            kind = MediaAlbumKindUi.Private,
            items = state.privateItems,
            isUploading = state.isUploadingPrivate,
            onAdd = onAddPrivate,
        )

        privateGrantsSection(
            grants = state.privateGrants,
            revokingGranteeIds = state.revokingGranteeIds,
            onRevokeGrant = onRevokeGrant,
        )
    }
}
