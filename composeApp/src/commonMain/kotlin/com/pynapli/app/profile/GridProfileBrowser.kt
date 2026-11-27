package com.pynapli.app.profile

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.design.PynapliBottomSheet
import com.pynapli.app.design.PynapliWideSheetPresentation
import com.pynapli.app.design.PynapliWindowSizeClass
import com.pynapli.app.design.currentPynapliWindowSizeClass
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.automirrored.filled.ArrowBack
import com.pynapli.app.design.icons.automirrored.filled.ArrowForward
import com.pynapli.app.design.icons.filled.Lock
import com.pynapli.app.grid.GridLinkedPartnerUi
import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.shared.settings.AppSettings
import kotlinx.coroutines.launch

internal object GridProfileBrowserTags {
    const val PAGER = "grid-profile-pager"
    const val PREVIOUS = "grid-profile-previous"
    const val NEXT = "grid-profile-next"
    const val LOCKED_PAGE = "grid-profile-locked-page"
}

/**
 * The Grid's profile surface (GRID_IMPLEMENTATION_SPEC "Profile open"). A profile opened from the
 * grid's [cards] is a page in a horizontal pager over those cards in grid order: full screen on
 * phones, the right-hand panel with Previous/Next and the Left/Right arrow keys on wider windows.
 * A profile opened from outside the list (a linked partner) falls back to the single-profile sheet.
 *
 * Each page paints from its card at once; the full profile is fetched only when a page settles
 * ([ProfilePagerController.onPageSettled]), which is also when the server records the view. Paging
 * near the end calls [onLoadMore], the grid's own next-page load. Blocking or hiding someone drops
 * them from the grid ([onRemoveCard]) and the pager moves on to the next person.
 *
 * Always composed by the Grid (it draws nothing while no profile is open), so the block/hide
 * handoff runs even after the sheet closed.
 */
@Composable
fun GridProfileBrowser(
    profileState: ProfileDetailUiState,
    profileDetailStateHolder: ProfileDetailStateHolder,
    conversationStateHolder: ConversationStateHolder,
    cards: List<GridProfileCardUi>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onRemoveCard: suspend (userId: String) -> Unit,
    viewerUserId: String?,
    profileLinkActionFor: (targetUserId: String?) -> ProfileLinkActionState?,
    onInviteProfileLink: (targetUserId: String) -> Unit,
    distanceUnit: String = AppSettings.UNIT_METRIC,
    // Opens the existing photo-verification flow; null hides "Verify to message".
    onOpenPhotoVerification: (() -> Unit)? = null,
    // Section 3's locked cards open the paywall from their page.
    onOpenStore: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val controller = remember { ProfilePagerController() }
    var removed by remember { mutableStateOf(emptySet<String>()) }
    val selected = profileState.selectedCard
    val model = remember(cards, selected, removed) { controller.reconcile(cards, selected, removed) }
    val currentModel by rememberUpdatedState(model)

    // Blocked or hidden from this surface: the grid drops them, and the pager moves on instead of
    // closing (the single-profile sheet still closes, as before).
    suspend fun handleRemoval(userId: String) {
        onRemoveCard(userId)
        val paging =
            currentModel != null && profileDetailStateHolder.state.value.selectedCard
                ?.id == userId
        if (paging) removed = removed + userId else profileDetailStateHolder.dismiss()
    }
    LaunchedEffect(profileState.blockedTargetUserId) {
        val blockedId = profileDetailStateHolder.consumeBlockedTargetUserId() ?: return@LaunchedEffect
        handleRemoval(blockedId)
    }
    LaunchedEffect(profileState.hiddenTargetUserId) {
        val hiddenId = profileDetailStateHolder.consumeHiddenTargetUserId() ?: return@LaunchedEffect
        handleRemoval(hiddenId)
    }
    LaunchedEffect(model?.replacement?.id, model?.closeRequested) {
        val next = model?.replacement
        when {
            next != null -> profileDetailStateHolder.open(next)
            model?.closeRequested == true -> profileDetailStateHolder.dismiss()
        }
    }
    LaunchedEffect(selected == null) {
        if (selected == null) removed = emptySet()
    }

    // The first-message composer follows whoever's profile is loaded on the settled page.
    val composerHolder =
        remember(conversationStateHolder) {
            ProfileComposerStateHolder(send = { target, body, key -> conversationStateHolder.sendFirstGridMessage(target, body, key) })
        }
    val composerState by composerHolder.state.collectAsState()
    val loadedUserId = profileState.profile?.userId?.takeIf { it == selected?.id }
    LaunchedEffect(loadedUserId) { composerHolder.bind(loadedUserId) }
    val composer =
        ProfileComposerUi(
            draft = composerState.draft,
            canSend = composerState.canSend,
            isSending = composerState.isSending,
            errorMessage = composerState.errorMessage,
            onDraftChange = composerHolder::updateDraft,
            onSend = {
                scope.launch {
                    val conversationId = composerHolder.sendDraft() ?: return@launch
                    // Land in the thread, as Message does; it refetches the conversation over HTTP.
                    profileDetailStateHolder.dismiss()
                    conversationStateHolder.openConversationById(conversationId, viewerUserId)
                }
            },
        )
    val onVerifyToMessage =
        onOpenPhotoVerification?.let { open ->
            {
                profileDetailStateHolder.dismiss()
                open()
            }
        }

    if (!profileState.visible) return

    @Composable
    fun OpenProfile(state: ProfileDetailUiState) {
        ProfileDetailContent(
            state = state,
            onDismiss = profileDetailStateHolder::dismiss,
            onSpark = { scope.launch { profileDetailStateHolder.sendSpark() } },
            onRequestAlbum = { scope.launch { profileDetailStateHolder.requestAlbumAccess() } },
            onViewPrivateAlbum = { scope.launch { profileDetailStateHolder.loadPrivateAlbum() } },
            onMessage = {
                val card = state.selectedCard
                if (card != null) {
                    profileDetailStateHolder.dismiss()
                    scope.launch { conversationStateHolder.openDirect(card = card, viewerUserId = viewerUserId) }
                }
            },
            onBlock = { reason -> scope.launch { profileDetailStateHolder.blockUser(reason) } },
            onHide = { scope.launch { profileDetailStateHolder.hideProfile() } },
            onReportAlbum = { albumId, category -> scope.launch { profileDetailStateHolder.submitAlbumReport(albumId, category) } },
            onReport = { category, note -> scope.launch { profileDetailStateHolder.submitReport(category, note) } },
            profileLinkAction = profileLinkActionFor(state.profile?.userId ?: state.selectedCard?.id),
            onInviteProfileLink = {
                (state.profile?.userId ?: state.selectedCard?.id)?.let(onInviteProfileLink)
            },
            onOpenLinkedPartner = { partner: GridLinkedPartnerUi ->
                scope.launch { profileDetailStateHolder.openLinkedPartner(partner) }
            },
            distanceUnit = distanceUnit,
            onToggleFavorite = { scope.launch { profileDetailStateHolder.toggleFavorite() } },
            composer = composer,
            onVerifyToMessage = onVerifyToMessage,
            photosByTap = model != null,
        )
    }

    if (model == null) {
        // Not one of the grid's cards (a linked partner): today's single-profile sheet.
        PynapliBottomSheet(
            onDismiss = profileDetailStateHolder::dismiss,
            widePresentation = PynapliWideSheetPresentation.SidePanel,
        ) {
            OpenProfile(profileState)
        }
        return
    }

    PynapliBottomSheet(
        onDismiss = profileDetailStateHolder::dismiss,
        widePresentation = PynapliWideSheetPresentation.SidePanel,
        compactFullScreen = true,
    ) {
        val pages by rememberUpdatedState(model.pages)
        val currentHasMore by rememberUpdatedState(hasMore)
        val currentIsLoadingMore by rememberUpdatedState(isLoadingMore)
        val pagerState = rememberPagerState(initialPage = model.currentIndex, pageCount = { pages.size })

        // The selection moved without a swipe (a block moved on, a partner who is also on the grid
        // was opened): bring the pager to that card's page.
        LaunchedEffect(model.currentIndex, selected?.id) {
            if (pagerState.currentPage != model.currentIndex && !pagerState.isScrollInProgress) {
                pagerState.scrollToPage(model.currentIndex)
            }
        }
        // Side effects belong to the page the viewer stopped on, never to a page only composed while
        // passing through.
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collect { index ->
                val effects = controller.onPageSettled(index, hasMore = currentHasMore, isLoadingMore = currentIsLoadingMore)
                effects.open?.let { card -> launch { profileDetailStateHolder.open(card) } }
                if (effects.loadMore) onLoadMore()
            }
        }

        val wide = currentPynapliWindowSizeClass() != PynapliWindowSizeClass.Compact
        val focus = remember { FocusRequester() }
        var selfFocused by remember { mutableStateOf(false) }
        LaunchedEffect(wide) {
            if (wide) runCatching { focus.requestFocus() }
        }

        fun step(delta: Int) {
            val target = pagerState.currentPage + delta
            if (target in pages.indices) scope.launch { pagerState.animateScrollToPage(target) }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    // Same focus model as closeOnEscape: keys act only while the surface itself holds
                    // focus, so the composer keeps its caret keys and Esc.
                    .focusRequester(focus)
                    .onFocusChanged { selfFocused = it.isFocused }
                    .focusable()
                    .onKeyEvent { event ->
                        if (!selfFocused || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> {
                                step(-1)
                                true
                            }

                            Key.DirectionRight -> {
                                step(1)
                                true
                            }

                            Key.Escape -> {
                                profileDetailStateHolder.dismiss()
                                true
                            }

                            else -> false
                        }
                    },
        ) {
            if (wide) {
                PagerNavRow(
                    canGoBack = pagerState.currentPage > 0,
                    canGoForward = pagerState.currentPage < pages.lastIndex,
                    onPrevious = { step(-1) },
                    onNext = { step(1) },
                )
            }
            HorizontalPager(
                state = pagerState,
                // Keyed by person, so a page removed or appended underneath keeps everyone else in place.
                key = { index -> pages.getOrNull(index)?.id ?: index },
                // A send in flight belongs to this person; finish it before moving on.
                userScrollEnabled = !composerState.isSending,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(GridProfileBrowserTags.PAGER),
            ) { index ->
                val card = pages.getOrNull(index) ?: return@HorizontalPager
                when {
                    // The plans sheet replaces the pager rather than stacking over it.
                    controller.isLocked(card) ->
                        LockedProfilePage(
                            onOpenStore = {
                                profileDetailStateHolder.dismiss()
                                onOpenStore()
                            },
                        )
                    card.id == profileState.selectedCard?.id -> OpenProfile(profileState)
                    // Passing through: the card's own data, no fetch and no view recorded.
                    else -> OpenProfile(ProfileDetailUiState(selectedCard = card, isLoading = true))
                }
            }
        }
    }
}

@Composable
private fun PagerNavRow(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(
            onClick = onPrevious,
            enabled = canGoBack,
            modifier = Modifier.handCursor(enabled = canGoBack).testTag(GridProfileBrowserTags.PREVIOUS),
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = "Previous", modifier = Modifier.padding(start = 6.dp), maxLines = 1)
        }
        TextButton(
            onClick = onNext,
            enabled = canGoForward,
            modifier = Modifier.handCursor(enabled = canGoForward).testTag(GridProfileBrowserTags.NEXT),
        ) {
            Text(text = "Next", modifier = Modifier.padding(end = 6.dp), maxLines = 1)
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Section 3: the pager's last page when the list reaches locked cards. It sells the plan instead of
 * showing a profile; nothing is fetched for it.
 */
@Composable
private fun LockedProfilePage(onOpenStore: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(GridProfileBrowserTags.LOCKED_PAGE),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = "Unlock more profiles",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onOpenStore, modifier = Modifier.handCursor()) {
                Text("See plans")
            }
        }
    }
}
