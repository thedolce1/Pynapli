package com.pynapli.app.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.profilelink.ProfileLinkUi
import com.pynapli.app.profilelink.ProfileLinksStateHolder
import com.pynapli.app.profilelink.ProfileLinksUiState

@Composable
fun MediaAlbumsScreen(
    stateHolder: MediaAlbumsStateHolder,
    profileLinksStateHolder: ProfileLinksStateHolder,
    onBack: () -> Unit,
    onPickMedia: (MediaAlbumKindUi) -> Unit,
    modifier: Modifier = Modifier
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
        modifier = modifier
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
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            MediaAlbumsHeader(onBack = onBack)
        }

        state.errorMessage?.let { message ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                ErrorBand(
                    message = message,
                    onDismiss = onClearError
                )
            }
        }

        profileLinksSection(
            state = profileLinksState,
            onAccept = onAcceptProfileLink,
            onDecline = onDeclineProfileLink,
            onUnlink = onUnlinkProfile,
            onClearMessage = onClearProfileLinkMessage
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
            onAdd = onAddProfile
        )

        albumSection(
            kind = MediaAlbumKindUi.Private,
            items = state.privateItems,
            isUploading = state.isUploadingPrivate,
            onAdd = onAddPrivate
        )

        privateGrantsSection(
            grants = state.privateGrants,
            revokingGranteeIds = state.revokingGranteeIds,
            onRevokeGrant = onRevokeGrant
        )
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.profileLinksSection(
    state: ProfileLinksUiState,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onUnlink: () -> Unit,
    onClearMessage: () -> Unit
) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        ProfileLinksSection(
            state = state,
            onAccept = onAccept,
            onDecline = onDecline,
            onUnlink = onUnlink,
            onClearMessage = onClearMessage
        )
    }
}

@Composable
private fun ProfileLinksSection(
    state: ProfileLinksUiState,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onUnlink: () -> Unit,
    onClearMessage: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProfileLinksHeader()

        state.errorMessage?.let { message ->
            LinkMessageBand(
                message = message,
                isError = true,
                onDismiss = onClearMessage
            )
        }
        state.feedbackMessage?.let { message ->
            LinkMessageBand(
                message = message,
                isError = false,
                onDismiss = onClearMessage
            )
        }

        when {
            state.isLoading && state.current == null &&
                state.incomingInvites.isEmpty() &&
                state.outgoingInvites.isEmpty() -> LoadingLinkedProfileRow()

            state.current != null -> ProfileLinkRow(
                link = state.current,
                statusLabel = "Active",
                isBusy = state.current.linkId in state.mutatingLinkIds,
                primaryActionLabel = "Unlink",
                onPrimaryAction = onUnlink
            )

            else -> EmptyLinkedProfileRow()
        }

        if (state.incomingInvites.isNotEmpty()) {
            Text(
                text = "Invites to review",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 2.dp)
            )
            state.incomingInvites.forEach { invite ->
                IncomingProfileLinkRow(
                    link = invite,
                    isBusy = invite.linkId in state.mutatingLinkIds,
                    onAccept = { onAccept(invite.linkId) },
                    onDecline = { onDecline(invite.linkId) }
                )
            }
        }

        if (state.outgoingInvites.isNotEmpty()) {
            Text(
                text = "Sent invites",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 2.dp)
            )
            state.outgoingInvites.forEach { invite ->
                ProfileLinkRow(
                    link = invite,
                    statusLabel = "Pending",
                    isBusy = false,
                    primaryActionLabel = null,
                    onPrimaryAction = {}
                )
            }
        }
    }
}

@Composable
private fun ProfileLinksHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.People,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "Linked profile",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ProfileLinkRow(
    link: ProfileLinkUi,
    statusLabel: String,
    isBusy: Boolean,
    primaryActionLabel: String?,
    onPrimaryAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ProfileLinkAvatar(link = link)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = link.displayLabel,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = statusLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
        when {
            isBusy -> CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )

            primaryActionLabel != null -> TextButton(onClick = onPrimaryAction) {
                Text(primaryActionLabel)
            }
        }
    }
}

@Composable
private fun IncomingProfileLinkRow(
    link: ProfileLinkUi,
    isBusy: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ProfileLinkAvatar(link = link)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = link.displayLabel,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Incoming invite",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
        if (isBusy) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
        } else {
            TextButton(onClick = onDecline) {
                Text("Decline")
            }
            TextButton(onClick = onAccept) {
                Text("Accept")
            }
        }
    }
}

@Composable
private fun ProfileLinkAvatar(link: ProfileLinkUi) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        if (link.otherPhotoUrl != null) {
            AsyncImage(
                model = link.otherPhotoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun EmptyLinkedProfileRow() {
    Text(
        text = "No linked profile.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun LoadingLinkedProfileRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "Loading linked profile",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun LinkMessageBand(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.38f), RoundedCornerShape(8.dp))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            color = color,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.albumSection(
    kind: MediaAlbumKindUi,
    items: List<MediaAlbumItemUi>,
    isUploading: Boolean,
    onAdd: () -> Unit
) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        AlbumSectionHeader(kind = kind)
    }

    item {
        AddMediaTile(
            kind = kind,
            isUploading = isUploading,
            onClick = onAdd
        )
    }

    items(
        items = items,
        key = { item -> item.mediaAssetId }
    ) { item ->
        MediaTile(item = item)
    }

    if (items.isEmpty() && !isUploading) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyAlbumRow(kind = kind)
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.privateGrantsSection(
    grants: List<MediaPrivateGrantUi>,
    revokingGranteeIds: Set<String>,
    onRevokeGrant: (String) -> Unit
) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        PrivateGrantsSectionHeader()
    }

    if (grants.isEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyGrantsRow()
        }
        return
    }

    items(
        items = grants,
        key = { grant -> grant.granteeUserId },
        span = { GridItemSpan(maxLineSpan) }
    ) { grant ->
        PrivateGrantRow(
            grant = grant,
            isRevoking = grant.granteeUserId in revokingGranteeIds,
            onRevoke = { onRevokeGrant(grant.granteeUserId) }
        )
    }
}

@Composable
private fun PrivateGrantsSectionHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.People,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "Granted access",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PrivateGrantRow(
    grant: MediaPrivateGrantUi,
    isRevoking: Boolean,
    onRevoke: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = grant.displayLabel,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (isRevoking) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.error,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
        } else {
            TextButton(onClick = onRevoke) {
                Icon(
                    imageVector = Icons.Filled.PersonOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Revoke",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyGrantsRow() {
    Text(
        text = "No one has access to your private album.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
    )
}

@Composable
private fun MediaAlbumsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Profile",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Linked profile and media",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AlbumSectionHeader(kind: MediaAlbumKindUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (kind == MediaAlbumKindUi.Private) Icons.Filled.Lock else Icons.Filled.PhotoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = kind.title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun AddMediaTile(
    kind: MediaAlbumKindUi,
    isUploading: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable(enabled = !isUploading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isUploading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(26.dp)
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Add ${kind.title}",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    text = "Add",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun MediaTile(item: MediaAlbumItemUi) {
    val tone = item.mediaAssetId.tone()
    val mediaUrl = item.mediaUrl
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(tone)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
    ) {
        if (mediaUrl != null && item.mediaType == "image") {
            AsyncImage(
                model = mediaUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = if (item.processingStatus == "pending_upload") Icons.Filled.CloudUpload else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.86f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
            )
        }
        if (item.albumKind == MediaAlbumKindUi.Private) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.54f))
                    .padding(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Private",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Text(
            text = item.statusLabel,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.54f))
                .padding(horizontal = 7.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun EmptyAlbumRow(kind: MediaAlbumKindUi) {
    Text(
        text = if (kind == MediaAlbumKindUi.Private) "No private media." else "No profile media.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
    )
}

@Composable
private fun LoadingAlbumRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "Loading albums",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ErrorBand(
    message: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.16f))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun String.tone(): Brush {
    val tones = listOf(
        Brush.linearGradient(listOf(Color(0xFF2A2622), Color(0xFFB08A44))),
        Brush.linearGradient(listOf(Color(0xFF17211E), Color(0xFF486C5D))),
        Brush.linearGradient(listOf(Color(0xFF251D22), Color(0xFF854D62))),
        Brush.linearGradient(listOf(Color(0xFF191F24), Color(0xFF53616D))),
        Brush.linearGradient(listOf(Color(0xFF20201E), Color(0xFF72634F)))
    )
    val index = fold(0) { total, char -> total + char.code }.mod(tones.size)
    return tones[index]
}
