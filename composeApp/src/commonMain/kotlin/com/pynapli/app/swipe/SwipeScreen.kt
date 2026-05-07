package com.pynapli.app.swipe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.grid.GridCardTone
import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.app.grid.LinkedPartnerTone
import com.pynapli.app.grid.toUi
import com.pynapli.app.profile.ProfileDetailActionMode
import com.pynapli.app.profile.ProfileDetailSheet
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.shared.distance.formatDistanceForUnit
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.settings.AppSettings
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@Composable
fun SwipeScreen(
    stateHolder: SwipeStateHolder,
    profileDetailStateHolder: ProfileDetailStateHolder,
    conversationStateHolder: ConversationStateHolder,
    viewerUserId: String?,
    distanceUnit: String = AppSettings.UNIT_METRIC,
    matchNotificationsEnabled: Boolean = true,
    notificationDetail: String = AppSettings.NOTIFICATION_DISCREET,
    onOpenStore: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by stateHolder.state.collectAsState()
    val profileState by profileDetailStateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()
    var requestedAction by remember { mutableStateOf<String?>(null) }
    var selectedProfileCard by remember { mutableStateOf<SwipeCardDto?>(null) }
    var profileActionMode by remember { mutableStateOf(ProfileDetailActionMode.SwipeUnmatched) }
    var introNoteCard by remember { mutableStateOf<SwipeCardDto?>(null) }
    var introNoteText by remember { mutableStateOf("") }

    LaunchedEffect(stateHolder) {
        stateHolder.load()
    }

    LaunchedEffect(state.pendingMatch, matchNotificationsEnabled) {
        if (!matchNotificationsEnabled && state.pendingMatch != null) {
            stateHolder.dismissMatch()
        }
    }

    LaunchedEffect(profileState.blockedTargetUserId) {
        val blockedId = profileDetailStateHolder.consumeBlockedTargetUserId() ?: return@LaunchedEffect
        stateHolder.removeCardById(blockedId)
        selectedProfileCard = null
        profileActionMode = ProfileDetailActionMode.SwipeUnmatched
        profileDetailStateHolder.dismiss()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        SwipeHeader()
        Spacer(Modifier.height(10.dp))
        SwipeFilterRow(
            filters = state.filters,
            onFilterSelected = { filter -> scope.launch { stateHolder.selectFilter(filter) } }
        )
        state.paywallMessage?.let { message ->
            SwipePaywallRow(
                message = message,
                onOpenStore = onOpenStore
            )
        }
        if (state.adSupported) {
            SwipeAdSlot()
        }
        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            when {
                state.isLoading && state.cards.isEmpty() -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                state.errorMessage != null && state.cards.isEmpty() -> {
                    SwipeError(
                        message = state.errorMessage ?: "Could not load swipe deck.",
                        onRetry = { scope.launch { stateHolder.load() } }
                    )
                }
                !state.hasMore -> {
                    EmptyDeck(onRefresh = { scope.launch { stateHolder.load() } })
                }
                else -> {
                    SwipeCardStack(
                        cards = state.cards,
                        enabled = !state.isSubmitting,
                        requestedAction = requestedAction,
                        distanceUnit = distanceUnit,
                        onActionConsumed = { requestedAction = null },
                        onOpenProfile = { card ->
                            profileActionMode = ProfileDetailActionMode.SwipeUnmatched
                            selectedProfileCard = card
                            scope.launch {
                                profileDetailStateHolder.open(card.toProfileCardUi())
                            }
                        },
                        onSwiped = { card, action ->
                            scope.launch { stateHolder.submitAction(card, action) }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (state.errorMessage != null && state.cards.isNotEmpty()) {
            SwipeInlineError(message = state.errorMessage ?: "")
            Spacer(Modifier.height(8.dp))
        }

        SwipeActions(
            enabled = state.hasMore && !state.isSubmitting,
            isSubmitting = state.isSubmitting,
            onPass = { requestedAction = SwipeAction.PASS },
            onIntroNote = {
                state.topCard?.let { card ->
                    introNoteCard = card
                    introNoteText = ""
                }
            },
            onPineappleLike = { requestedAction = SwipeAction.PINEAPPLE_LIKE },
            onLike = { requestedAction = SwipeAction.LIKE }
        )
    }

    if (matchNotificationsEnabled) state.pendingMatch?.let { match ->
        MatchOverlay(
            match = match,
            showDetails = notificationDetail == AppSettings.NOTIFICATION_STANDARD,
            onDismiss = stateHolder::dismissMatch,
            onMessage = {
                stateHolder.dismissMatch()
                scope.launch {
                    conversationStateHolder.openSwipeMatch(
                        match = match,
                        viewerUserId = viewerUserId
                    )
                }
            }
        )
    }

    if (profileState.visible && (selectedProfileCard != null || profileActionMode == ProfileDetailActionMode.Grid)) {
        val dismissProfile = {
            selectedProfileCard = null
            profileActionMode = ProfileDetailActionMode.SwipeUnmatched
            profileDetailStateHolder.dismiss()
        }

        ProfileDetailSheet(
            state = profileState,
            onDismiss = dismissProfile,
            onMessage = {
                val card = profileState.selectedCard
                if (card != null) {
                    dismissProfile()
                    scope.launch {
                        conversationStateHolder.openDirect(
                            card = card,
                            viewerUserId = viewerUserId
                        )
                    }
                }
            },
            onSpark = {
                scope.launch { profileDetailStateHolder.sendSpark() }
            },
            onRequestAlbum = {
                scope.launch { profileDetailStateHolder.requestAlbumAccess() }
            },
            onViewPrivateAlbum = {
                scope.launch { profileDetailStateHolder.loadPrivateAlbum() }
            },
            onBlock = { reason ->
                scope.launch { profileDetailStateHolder.blockUser(reason) }
            },
            onReport = { category, note ->
                scope.launch { profileDetailStateHolder.submitReport(category, note) }
            },
            actionMode = profileActionMode,
            isSwipeSubmitting = state.isSubmitting,
            onSwipePass = {
                val card = selectedProfileCard
                if (card != null) {
                    dismissProfile()
                    scope.launch { stateHolder.submitAction(card, SwipeAction.PASS) }
                }
            },
            onSwipeLike = {
                val card = selectedProfileCard
                if (card != null) {
                    dismissProfile()
                    scope.launch { stateHolder.submitAction(card, SwipeAction.LIKE) }
                }
            },
            onOpenLinkedPartner = { partner ->
                selectedProfileCard = null
                profileActionMode = ProfileDetailActionMode.Grid
                scope.launch { profileDetailStateHolder.openLinkedPartner(partner) }
            },
            distanceUnit = distanceUnit
        )
    }

    introNoteCard?.let { card ->
        IntroNoteDialog(
            displayName = card.displayName,
            noteText = introNoteText,
            isSubmitting = state.isSubmitting,
            onNoteTextChanged = { text -> introNoteText = text.take(INTRO_NOTE_INPUT_MAX_LENGTH) },
            onDismiss = {
                if (!state.isSubmitting) {
                    introNoteCard = null
                    introNoteText = ""
                }
            },
            onSend = {
                scope.launch {
                    stateHolder.submitIntroNote(card, introNoteText)
                    introNoteCard = null
                    introNoteText = ""
                }
            }
        )
    }
}

@Composable
private fun SwipeHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Style,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Swipe",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Pass or like nearby profiles",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SwipeFilterRow(
    filters: List<SwipeFilterUi>,
    onFilterSelected: (SwipeFilterType) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filters.forEach { filter ->
            item(key = filter.type.name) {
                AssistChip(
                    onClick = { onFilterSelected(filter.type) },
                    label = {
                        Text(
                            text = filter.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = if (filter.locked) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        null
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (filter.active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        labelColor = when {
                            filter.active -> MaterialTheme.colorScheme.onPrimary
                            filter.locked -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        leadingIconContentColor = if (filter.active) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    ),
                    border = AssistChipDefaults.assistChipBorder(
                        enabled = true,
                        borderColor = MaterialTheme.colorScheme.outline
                    )
                )
            }
        }
    }
}

@Composable
private fun SwipePaywallRow(
    message: String,
    onOpenStore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onOpenStore) {
            Text(text = "Social", maxLines = 1)
        }
    }
}

@Composable
private fun SwipeAdSlot() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Sponsored",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun SwipeInlineError(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SwipeCardStack(
    cards: List<SwipeCardDto>,
    enabled: Boolean,
    requestedAction: String?,
    distanceUnit: String,
    onActionConsumed: () -> Unit,
    onOpenProfile: (SwipeCardDto) -> Unit,
    onSwiped: (SwipeCardDto, String) -> Unit
) {
    val visibleStack = cards.take(3)
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            visibleStack.asReversed().forEachIndexed { reverseIndex, card ->
                val depth = visibleStack.lastIndex - reverseIndex
                SwipeCard(
                    card = card,
                    depth = depth,
                    isTop = depth == 0,
                    enabled = enabled,
                    widthPx = widthPx,
                    requestedAction = if (depth == 0) requestedAction else null,
                    distanceUnit = distanceUnit,
                    onActionConsumed = onActionConsumed,
                    onOpenProfile = onOpenProfile,
                    onSwiped = onSwiped
                )
            }
        }
    }
}

@Composable
private fun SwipeCard(
    card: SwipeCardDto,
    depth: Int,
    isTop: Boolean,
    enabled: Boolean,
    widthPx: Float,
    requestedAction: String?,
    distanceUnit: String,
    onActionConsumed: () -> Unit,
    onOpenProfile: (SwipeCardDto) -> Unit,
    onSwiped: (SwipeCardDto, String) -> Unit
) {
    val verticalOffset = (depth * 8).dp
    val horizontalInset = (depth * 6).dp
    val tone = card.userId.toBackgroundBrush()
    val scope = rememberCoroutineScope()
    val offset = remember(card.userId) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val threshold = widthPx * 0.26f

    suspend fun settleBack() {
        offset.animateTo(Offset.Zero, tween(durationMillis = 180))
    }

    suspend fun flyOut(action: String) {
        val direction = if (action == SwipeAction.PASS) -1f else 1f
        offset.animateTo(
            targetValue = Offset(
                x = direction * widthPx * 1.25f,
                y = offset.value.y
            ),
            animationSpec = tween(durationMillis = 210)
        )
        onSwiped(card, action)
    }

    LaunchedEffect(requestedAction, card.userId) {
        val action = requestedAction ?: return@LaunchedEffect
        onActionConsumed()
        flyOut(action)
    }

    val dragModifier = if (isTop && enabled) {
        Modifier.pointerInput(card.userId, widthPx) {
            detectDragGestures(
                onDrag = { change, dragAmount ->
                    change.consume()
                    scope.launch {
                        offset.snapTo(
                            offset.value + Offset(
                                x = dragAmount.x,
                                y = dragAmount.y * 0.28f
                            )
                        )
                    }
                },
                onDragEnd = {
                    scope.launch {
                        val action = when {
                            offset.value.x > threshold -> SwipeAction.LIKE
                            offset.value.x < -threshold -> SwipeAction.PASS
                            else -> null
                        }
                        if (action == null) {
                            settleBack()
                        } else {
                            flyOut(action)
                        }
                    }
                },
                onDragCancel = {
                    scope.launch { settleBack() }
                }
            )
        }
    } else {
        Modifier
    }

    val decisionColor = if (offset.value.x >= 0f) Color(0xFF1FBF7B) else Color(0xFFE85A4A)
    val decisionLabel = if (offset.value.x >= 0f) "Like" else "Pass"
    val decisionAlpha = (offset.value.x.absoluteValue / threshold).coerceIn(0f, 1f)
    val distanceLabel = formatDistanceForUnit(
        distanceMeters = card.distanceMeters,
        fallbackLabel = card.distanceLabel,
        distanceUnit = distanceUnit
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = horizontalInset,
                end = horizontalInset,
                top = verticalOffset
            )
            .graphicsLayer {
                if (isTop) {
                    translationX = offset.value.x
                    translationY = offset.value.y
                    rotationZ = (offset.value.x / 42f).coerceIn(-10f, 10f)
                }
            }
            .then(dragModifier)
            .clickable(
                enabled = isTop && enabled,
                onClick = { onOpenProfile(card) }
            )
            .clip(RoundedCornerShape(8.dp))
            .background(tone)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
    ) {
        card.photoUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        val linkedPartner = card.linkedPartner
        if (linkedPartner != null && decisionAlpha < 0.12f) {
            SwipeLinkedPartnerBadge(
                thumbnailUrl = linkedPartner.thumbnailUrl,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(18.dp)
            )
        }
        if (isTop && decisionAlpha > 0f) {
            Text(
                text = decisionLabel,
                color = decisionColor,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(if (offset.value.x >= 0f) Alignment.TopStart else Alignment.TopEnd)
                    .padding(22.dp)
                    .graphicsLayer { alpha = decisionAlpha }
                    .border(2.dp, decisionColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xE6000000))
                    )
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = card.displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                card.displayAge?.let { age ->
                    Text(
                        text = "$age",
                        color = Color(0xFFEDEDED),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            distanceLabel?.let { distance ->
                Text(
                    text = distance,
                    color = Color(0xFFCFCFCF),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun SwipeLinkedPartnerBadge(
    thumbnailUrl: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(0xCC1B1B1B))
            .border(1.dp, Color(0xCCFFFFFF), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnailUrl != null) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = "Linked partner",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = "Linked partner",
                tint = Color.White,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

@Composable
private fun SwipeActions(
    enabled: Boolean,
    isSubmitting: Boolean,
    onPass: () -> Unit,
    onIntroNote: () -> Unit,
    onPineappleLike: () -> Unit,
    onLike: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, alignment = Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SwipeActionButton(
            enabled = enabled,
            isSubmitting = isSubmitting,
            icon = Icons.Filled.Close,
            contentDescription = "Pass",
            background = Color(0x33EE6650),
            iconTint = Color(0xFFE85A4A),
            onClick = onPass
        )
        SwipeActionButton(
            enabled = enabled,
            isSubmitting = isSubmitting,
            icon = Icons.Filled.Edit,
            contentDescription = "Intro Note",
            background = MaterialTheme.colorScheme.surfaceVariant,
            iconTint = MaterialTheme.colorScheme.primary,
            onClick = onIntroNote
        )
        SwipeActionButton(
            enabled = enabled,
            isSubmitting = isSubmitting,
            icon = Icons.Filled.Star,
            contentDescription = "Pineapple Like",
            background = Color(0x33F2B84B),
            iconTint = Color(0xFFF2B84B),
            onClick = onPineappleLike
        )
        SwipeActionButton(
            enabled = enabled,
            isSubmitting = isSubmitting,
            icon = Icons.Filled.Favorite,
            contentDescription = "Like",
            background = Color(0x331FBF7B),
            iconTint = Color(0xFF1FBF7B),
            onClick = onLike
        )
    }
}

@Composable
private fun IntroNoteDialog(
    displayName: String,
    noteText: String,
    isSubmitting: Boolean,
    onNoteTextChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit
) {
    val trimmed = noteText.trim()
    val canSend = trimmed.isNotEmpty() && trimmed.length <= INTRO_NOTE_MAX_LENGTH && !isSubmitting
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Intro Note",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Send a short note to $displayName before matching.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = noteText,
                    onValueChange = onNoteTextChanged,
                    enabled = !isSubmitting,
                    maxLines = 4,
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${trimmed.length}/$INTRO_NOTE_MAX_LENGTH",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSend,
                enabled = canSend
            ) {
                Text(text = if (isSubmitting) "Sending" else "Send")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SwipeActionButton(
    enabled: Boolean,
    isSubmitting: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    background: Color,
    iconTint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, iconTint.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(64.dp)
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    color = iconTint,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = iconTint,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyDeck(onRefresh: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Style,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier.size(56.dp)
        )
        Text(
            text = "No more profiles nearby",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Check back later or expand your radius.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Button(onClick = onRefresh) {
            Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text("Refresh")
        }
    }
}

@Composable
private fun SwipeError(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun MatchOverlay(
    match: MatchDto,
    showDetails: Boolean,
    onDismiss: () -> Unit,
    onMessage: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "It's a match",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (showDetails) {
                    match.matchedDisplayName ?: "You both liked each other."
                } else {
                    "You both liked each other."
                },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onMessage,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Message")
            }
            TextButton(
                onClick = onDismiss,
            ) {
                Text("Keep swiping")
            }
        }
    }
}

private fun String.toBackgroundBrush(): Brush {
    val tones = listOf(
        Brush.linearGradient(listOf(Color(0xFF2A2622), Color(0xFFB08A44))),
        Brush.linearGradient(listOf(Color(0xFF17211E), Color(0xFF486C5D))),
        Brush.linearGradient(listOf(Color(0xFF251D22), Color(0xFF854D62))),
        Brush.linearGradient(listOf(Color(0xFF191F24), Color(0xFF53616D))),
        Brush.linearGradient(listOf(Color(0xFF20201E), Color(0xFF72634F)))
    )
    val index = stableIndex(tones.size)
    return tones[index]
}

private fun SwipeCardDto.toProfileCardUi(): GridProfileCardUi {
    val toneIndex = userId.stableIndex(GridCardTone.entries.size)
    val linkedToneIndex = userId.stableIndex(LinkedPartnerTone.entries.size)
    return GridProfileCardUi(
        id = userId,
        displayName = displayName,
        thumbnailUrl = photoUrl,
        online = false,
        distanceLabel = distanceLabel,
        tone = GridCardTone.entries[toneIndex],
        linkedPartner = linkedPartner?.toUi(),
        linkedPartnerTone = linkedPartner?.let { LinkedPartnerTone.entries[linkedToneIndex] },
        distanceMeters = distanceMeters
    )
}

private fun String.stableIndex(size: Int): Int =
    fold(0) { total, char -> total + char.code }.mod(size)

private const val INTRO_NOTE_MAX_LENGTH = 140
private const val INTRO_NOTE_INPUT_MAX_LENGTH = 160
