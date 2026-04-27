package com.pynapli.app.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.media.MediaAlbumItemUi
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDetailSheet(
    state: ProfileDetailUiState,
    onDismiss: () -> Unit,
    onMessage: () -> Unit,
    onSpark: () -> Unit,
    onRequestAlbum: () -> Unit,
    onViewPrivateAlbum: () -> Unit,
    modifier: Modifier = Modifier
) {
    val card = state.selectedCard ?: return
    val profile = state.profile
    val displayName = profile?.displayName ?: card.displayName
    val distanceLabel = profile?.distanceLabel ?: card.distanceLabel
    val profilePhotoUrl = profile?.profilePhotoUrl ?: card.thumbnailUrl
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ProfileHero(
                displayName = displayName,
                online = card.online,
                distanceLabel = distanceLabel,
                profilePhotoUrl = profilePhotoUrl,
                onDismiss = onDismiss
            )

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                state.errorMessage != null -> {
                    Text(
                        text = state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                profile != null -> {
                    ProfileFacts(profileState = state)
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
                        onMessage = onMessage,
                        onSpark = onSpark,
                        onRequestAlbum = onRequestAlbum,
                        onViewPrivateAlbum = onViewPrivateAlbum
                    )
                    PrivateAlbumPreview(
                        items = state.privateAlbumItems,
                        isLoading = state.isLoadingPrivateAlbum
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileHero(
    displayName: String,
    online: Boolean,
    distanceLabel: String?,
    profilePhotoUrl: String?,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF2B2622), Color(0xFF6D5D4F), Color(0xFF1C1B1A))
                )
            )
    ) {
        profilePhotoUrl?.let { photoUrl ->
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(210.dp)
            )
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(42.dp)
                .clip(CircleShape)
                .background(Color(0x99000000))
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close profile",
                tint = Color.White
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xD9000000))
                    )
                )
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (online) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF13E58A))
                        .border(1.dp, Color.Black, CircleShape)
                )
            }

            Text(
                text = displayName,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            distanceLabel?.let {
                Text(
                    text = it,
                    color = Color(0xFFE5E5E5),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ProfileFacts(profileState: ProfileDetailUiState) {
    val card = profileState.selectedCard ?: return
    val profile = profileState.profile ?: return
    val chips = buildList {
        profile.displayAge?.let { add("$it") }
        add(profile.discoveryStyle.toDisplayLabel())
        profile.relationshipContext?.let { add(it.toDisplayLabel()) }
        profile.gender?.let { add(it.toDisplayLabel()) }
        profile.orientation?.let { add(it.toDisplayLabel()) }
        if (card.linkedPartnerTone != null) add("Linked")
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { label ->
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = if (label == "Linked") {
                    {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    null
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    leadingIconContentColor = MaterialTheme.colorScheme.primary
                ),
                border = AssistChipDefaults.assistChipBorder(
                    enabled = true,
                    borderColor = MaterialTheme.colorScheme.outline
                )
            )
        }
    }
}

@Composable
private fun ProfileActions(
    allowGridMessages: Boolean,
    allowFlirts: Boolean,
    allowAlbumRequests: Boolean,
    privateAlbumAccessStatus: String,
    isSendingFlirt: Boolean,
    isRequestingAlbum: Boolean,
    isLoadingPrivateAlbum: Boolean,
    flirtFeedback: String?,
    albumFeedback: String?,
    onMessage: () -> Unit,
    onSpark: () -> Unit,
    onRequestAlbum: () -> Unit,
    onViewPrivateAlbum: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onMessage,
            enabled = allowGridMessages,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Filled.ChatBubble,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Message",
                modifier = Modifier.padding(start = 6.dp),
                maxLines = 1
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onSpark,
                enabled = allowFlirts && !isSendingFlirt,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (isSendingFlirt) "Sending" else "Spark",
                    modifier = Modifier.padding(start = 6.dp),
                    maxLines = 1
                )
            }
            OutlinedButton(
                onClick = if (privateAlbumAccessStatus == "granted") onViewPrivateAlbum else onRequestAlbum,
                enabled = when (privateAlbumAccessStatus) {
                    "granted" -> !isLoadingPrivateAlbum
                    "pending" -> false
                    else -> allowAlbumRequests && !isRequestingAlbum
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = when {
                        privateAlbumAccessStatus == "granted" && isLoadingPrivateAlbum -> "Loading"
                        privateAlbumAccessStatus == "granted" -> "View"
                        privateAlbumAccessStatus == "pending" -> "Pending"
                        isRequestingAlbum -> "Requesting"
                        else -> "Request"
                    },
                    modifier = Modifier.padding(start = 6.dp),
                    maxLines = 1
                )
            }
        }
        (albumFeedback ?: flirtFeedback)?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PrivateAlbumPreview(
    items: List<MediaAlbumItemUi>,
    isLoading: Boolean
) {
    when {
        isLoading -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Loading private album",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        items.isNotEmpty() -> {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items.forEach { item ->
                    PrivateAlbumTile(item = item)
                }
            }
        }
    }
}

@Composable
private fun PrivateAlbumTile(item: MediaAlbumItemUi) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2B2622))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
    ) {
        if (item.mediaUrl != null && item.mediaType == "image") {
            AsyncImage(
                model = item.mediaUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.PhotoLibrary,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(24.dp)
            )
        }
    }
}

private fun String.toDisplayLabel(): String =
    split('_').joinToString(" ") { part ->
        part.take(1).uppercase() + part.drop(1)
    }
