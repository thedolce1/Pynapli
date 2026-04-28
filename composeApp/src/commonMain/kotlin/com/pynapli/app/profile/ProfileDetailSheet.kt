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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pynapli.shared.network.dto.ReportCategory
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
    onBlock: (reason: String?) -> Unit,
    onReport: (category: String, note: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val card = state.selectedCard ?: return
    val profile = state.profile
    val displayName = profile?.displayName ?: card.displayName
    val distanceLabel = profile?.distanceLabel ?: card.distanceLabel
    val profilePhotoUrl = profile?.profilePhotoUrl ?: card.thumbnailUrl
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showBlockConfirm by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }

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
                onDismiss = onDismiss,
                onBlockClick = { showBlockConfirm = true },
                onReportClick = { showReportDialog = true }
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
                    state.reportFeedback?.let { feedback ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = feedback,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    PrivateAlbumPreview(
                        items = state.privateAlbumItems,
                        isLoading = state.isLoadingPrivateAlbum
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
            onDismiss = { showBlockConfirm = false }
        )
    }

    if (showReportDialog) {
        ReportCategoryDialog(
            isSubmitting = state.isReporting,
            onSubmit = { category, note ->
                showReportDialog = false
                onReport(category, note)
            },
            onDismiss = { showReportDialog = false }
        )
    }
}

@Composable
private fun BlockConfirmDialog(
    displayName: String,
    isBlocking: Boolean,
    onConfirm: (reason: String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block $displayName?") },
        text = {
            Text(
                "They will not see you in the grid or messages, " +
                    "and any open conversation will be hidden."
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(null) },
                enabled = !isBlocking,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                if (isBlocking) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onError,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text("Block")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBlocking) { Text("Cancel") }
        }
    )
}

@Composable
private fun ReportCategoryDialog(
    isSubmitting: Boolean,
    onSubmit: (category: String, note: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report this profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Pick the closest reason. Moderators review reports.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ReportCategory.ALL.forEach { category ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        TextButton(
                            onClick = { selectedCategory = category },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = category.toReportLabel(),
                                color = if (selectedCategory == category) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (selectedCategory == category) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val category = selectedCategory ?: return@Button
                    onSubmit(category, null)
                },
                enabled = !isSubmitting && selectedCategory != null
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text("Submit")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Cancel") }
        }
    )
}

private fun String.toReportLabel(): String = when (this) {
    ReportCategory.UNDERAGE_USER -> "Underage user"
    ReportCategory.HARASSMENT_OR_THREATS -> "Harassment or threats"
    ReportCategory.EXPLICIT_PUBLIC_CONTENT -> "Explicit public content"
    ReportCategory.SPAM_OR_FAKE_PROFILE -> "Spam or fake profile"
    ReportCategory.IMPERSONATION -> "Impersonation"
    ReportCategory.SOLICITATION -> "Solicitation"
    ReportCategory.NON_CONSENSUAL_CONTENT -> "Non-consensual content"
    ReportCategory.PRIVATE_INFO_DOXXING -> "Private info or doxxing"
    ReportCategory.OTHER -> "Other"
    else -> this
}

@Composable
private fun ProfileHero(
    displayName: String,
    online: Boolean,
    distanceLabel: String?,
    profilePhotoUrl: String?,
    onDismiss: () -> Unit,
    onBlockClick: () -> Unit,
    onReportClick: () -> Unit
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

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ProfileOverflowMenu(
                onBlockClick = onBlockClick,
                onReportClick = onReportClick
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
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
private fun ProfileOverflowMenu(
    onBlockClick: () -> Unit,
    onReportClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color(0x99000000))
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More options",
                tint = Color.White
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Block") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    expanded = false
                    onBlockClick()
                }
            )
            DropdownMenuItem(
                text = { Text("Report") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Flag,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    expanded = false
                    onReportClick()
                }
            )
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
