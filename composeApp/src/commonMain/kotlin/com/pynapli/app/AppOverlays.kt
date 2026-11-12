package com.pynapli.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.conversation.ConversationSheet
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.design.closeOnEscape
import com.pynapli.app.grid.GridLinkedPartnerUi
import com.pynapli.app.grid.profileLinkActionFor
import com.pynapli.app.profile.ProfileDetailSheet
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profilelink.ProfileLinksStateHolder
import com.pynapli.app.psync.PsyncSessionScreen
import com.pynapli.app.psync.PsyncStateHolder
import com.pynapli.app.swipe.toProfileCardUi
import com.pynapli.app.tonight.PlayingTonightStateHolder
import com.pynapli.shared.network.PsyncStreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The full-screen overlays + bottom sheets that sit above [PynapliApp]'s main shell (extracted
 * from App.kt): the Playing Tonight surface, the Psync session surface (with its realtime stream
 * collection scoped to the open overlay), the Messages profile-peek sheet, and the standalone
 * conversation sheet. Behavior-preserving — moved verbatim, with the shell's `tonightVisible` /
 * `psyncVisible` writes replaced by dismiss callbacks. Per-surface UI state is collected from the
 * passed holders, so the shell no longer threads those values.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PynapliOverlays(
    tonightVisible: Boolean,
    onTonightDismiss: () -> Unit,
    psyncVisible: Boolean,
    onPsyncDismiss: () -> Unit,
    onPsyncOpenStore: () -> Unit = {},
    onPsyncOpenProfileHub: () -> Unit = {},
    psyncTravelCityName: String? = null,
    selectedTab: PynapliTab,
    playingTonightStateHolder: PlayingTonightStateHolder?,
    psyncStateHolder: PsyncStateHolder?,
    psyncStreamClient: PsyncStreamClient?,
    profileDetailStateHolder: ProfileDetailStateHolder,
    conversationStateHolder: ConversationStateHolder,
    profileLinksStateHolder: ProfileLinksStateHolder,
    authStateHolder: AuthStateHolder,
    appSettingsStateHolder: AppSettingsStateHolder,
    conversationActions: ConversationActions,
    onPickConversationPhoto: () -> Unit,
    scope: CoroutineScope,
) {
    val authState by authStateHolder.state.collectAsState()
    val conversationState by conversationStateHolder.state.collectAsState()
    val profileDetailState by profileDetailStateHolder.state.collectAsState()
    val profileLinksState by profileLinksStateHolder.state.collectAsState()
    val appSettingsState by appSettingsStateHolder.state.collectAsState()
    val conversationShownInline = selectedTab == PynapliTab.Messages && conversationState.visible
    // True while the profile peek on screen was opened from a Psync deck card.
    var psyncProfileOpen by remember { mutableStateOf(false) }

    if (tonightVisible && playingTonightStateHolder != null) {
        // System back (gesture/hardware) closes the full-screen overlay instead of falling
        // through to the Activity — otherwise the only exit is the small top-left back button.
        //
        // BackHandler is @Deprecated in CMP 1.11 ("Use NavigationEvent instead") and we are
        // deliberately staying on it. Investigated 2026-07-27, do not "fix" the warning without
        // re-checking these three facts:
        //
        //  1. The deprecation is WARNING level (no DeprecationLevel argument), so this still
        //     compiles and works on all five targets we build.
        //  2. NavigationEventHandler is NOT in org.jetbrains.compose.ui:ui-backhandler. It lives in
        //     androidx.navigationevent:navigationevent-compose, which is not on our classpath, and
        //     whose non-Android source sets are commonStubs that throw NotImplementedError:
        //     "Implemented only in JetBrains fork. Please use
        //     org.jetbrains.navigationevent:navigationevent-compose instead." Adopting it would
        //     break back handling on iOS, wasm and desktop at RUNTIME, not compile time.
        //  3. That JetBrains fork returns zero results on Maven Central — it ships only from a
        //     JetBrains dev channel, which the stable-releases-only policy in
        //     docs/OFFICIAL_TECH_REFERENCE_GUIDE.md rules out.
        //
        // The warning is intentionally left un-suppressed: it is the reminder to revisit once the
        // JetBrains artifact reaches a stable Maven Central release.
        BackHandler(onBack = onTonightDismiss)
        // Playing Tonight map surface, opened from the Grid header moon button. Profile/
        // message taps close the overlay and reuse the existing profile-detail path
        // (PLAYING_TONIGHT_SPEC: "tapping opens the relevant individual profile/session").
        com.pynapli.app.tonight.PlayingTonightScreen(
            stateHolder = playingTonightStateHolder,
            viewerUserId = authState.session?.userId,
            onBack = onTonightDismiss,
            onOpenProfile = { userId ->
                onTonightDismiss()
                scope.launch {
                    profileDetailStateHolder.openFromTonight(userId)
                }
            },
            onMessage = { userId ->
                onTonightDismiss()
                scope.launch {
                    profileDetailStateHolder.openFromTonight(userId)
                }
            },
            modifier = Modifier.fillMaxSize().closeOnEscape(onTonightDismiss),
        )
    }

    if (psyncVisible && psyncStateHolder != null) {
        BackHandler(onBack = onPsyncDismiss)
        // Psync synchronized-swipe surface, opened from the Swipe header (PSYNC_SPEC: Psync
        // lives in the Swipe/partner flow, not its own tab).
        psyncStreamClient?.let { client ->
            // Convenience realtime: refetch canonical state when the partner's side changes.
            // Scoped to the open overlay — leaving composition cancels the socket.
            LaunchedEffect(psyncStateHolder, client) {
                client.connect().collect { delta -> psyncStateHolder.onRealtimeDelta(delta.sessionId, delta.type) }
            }
        }
        // Realtime is a convenience and the stream does not replay what it missed while the socket
        // was down. While the viewer is waiting on their partner, refetch on a slow timer so the
        // screen always catches up (CLAUDE.md: HTTP fetch is canonical).
        val psyncWaiting =
            psyncStateHolder.state
                .collectAsState()
                .value.waitingOnPartner
        LaunchedEffect(psyncStateHolder, psyncWaiting) {
            while (psyncWaiting) {
                delay(PSYNC_WAITING_REFETCH_MILLIS)
                psyncStateHolder.refresh()
            }
        }
        // A block or hide from the profile peek invalidates that card for both partners; the
        // server already drops it from the deck, so refetch and close the peek.
        LaunchedEffect(profileDetailState.blockedTargetUserId, profileDetailState.hiddenTargetUserId) {
            if (psyncProfileOpen && (profileDetailState.blockedTargetUserId != null || profileDetailState.hiddenTargetUserId != null)) {
                psyncProfileOpen = false
                profileDetailStateHolder.dismiss()
                psyncStateHolder.refresh()
            }
        }
        PsyncSessionScreen(
            stateHolder = psyncStateHolder,
            onBack = onPsyncDismiss,
            modifier = Modifier.fillMaxSize().closeOnEscape(onPsyncDismiss),
            distanceUnit = appSettingsState.settings.distanceUnit,
            onOpenProfile = { card ->
                psyncProfileOpen = true
                scope.launch { profileDetailStateHolder.open(card.toProfileCardUi()) }
            },
            onOpenProfileById = { userId, displayName ->
                psyncProfileOpen = true
                scope.launch { profileDetailStateHolder.openLinkedPartner(GridLinkedPartnerUi(userId = userId, displayName = displayName)) }
            },
            onOpenStore = onPsyncOpenStore,
            onOpenProfileHub = onPsyncOpenProfileHub,
            travelCityName = psyncTravelCityName,
        )
    }

    // The Psync deck sits over whichever tab is underneath, and that tab only shows the peek for
    // profiles it opened itself, so the overlay hosts the peek for cards opened from the deck.
    val psyncOwnsProfilePeek = psyncVisible && psyncProfileOpen
    // Grid and Swipe host the peek themselves; Messages and Activity have no sheet of their own, so
    // without this a profile opened from an Activity tile, "View album" or a Messages avatar would
    // set the sheet state with nothing on screen (and pop up later on the Grid).
    val overlayOwnsProfilePeek = selectedTab == PynapliTab.Messages || selectedTab == PynapliTab.Activity
    if ((overlayOwnsProfilePeek || psyncOwnsProfilePeek) && profileDetailState.visible) {
        // Unified profile peek: the exact same ProfileDetailSheet as Grid/Swipe (one profile
        // experience app-wide), with block/report/hide and the shared sheet chrome.
        ProfileDetailSheet(
            state = profileDetailState,
            onDismiss = {
                psyncProfileOpen = false
                profileDetailStateHolder.dismiss()
            },
            onMessage = {
                val openedFromPsync = psyncProfileOpen
                psyncProfileOpen = false
                val card = profileDetailState.selectedCard
                profileDetailStateHolder.dismiss()
                // From Messages, "Message" just returns to the open thread; from Activity it starts
                // (or reopens) the conversation, as it does on the Grid.
                if (selectedTab == PynapliTab.Activity && !openedFromPsync && card != null) {
                    scope.launch { conversationStateHolder.openDirect(card = card, viewerUserId = authState.session?.userId) }
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
            onHide = {
                scope.launch { profileDetailStateHolder.hideProfile() }
            },
            onBlock = { reason ->
                scope.launch { profileDetailStateHolder.blockUser(reason) }
            },
            onReport = { category, note ->
                scope.launch { profileDetailStateHolder.submitReport(category, note) }
            },
            profileLinkAction =
                profileLinksState.profileLinkActionFor(
                    targetUserId = profileDetailState.profile?.userId ?: profileDetailState.selectedCard?.id,
                    viewerUserId = authState.session?.userId,
                ),
            onInviteProfileLink = {
                val targetUserId = profileDetailState.profile?.userId ?: profileDetailState.selectedCard?.id
                if (targetUserId != null) {
                    scope.launch { profileLinksStateHolder.createInvitation(targetUserId) }
                }
            },
            onOpenLinkedPartner = { partner ->
                scope.launch { profileDetailStateHolder.openLinkedPartner(partner) }
            },
            distanceUnit = appSettingsState.settings.distanceUnit,
        )
    }

    if (conversationState.visible && !conversationShownInline) {
        ConversationSheet(
            state = conversationState,
            onDismiss = conversationActions.dismiss,
            onDraftChanged = conversationActions.onDraftChanged,
            onSend = conversationActions.onSend,
            onLoadOlder = conversationActions.onLoadOlder,
            linkedPartnerUserId = profileLinksState.current?.otherUserId,
            onInvitePartner = conversationActions.onInvitePartner,
            onAcceptInvite = conversationActions.onAcceptInvite,
            onDeclineInvite = conversationActions.onDeclineInvite,
            onLeaveConversation = conversationActions.onLeave,
            onDeleteMessage = conversationActions.onDeleteMessage,
            onSetExpiry = conversationActions.onSetExpiry,
            onRevealMedia = conversationActions.onRevealMedia,
            onPickPhoto = onPickConversationPhoto,
        )
    }
}

// How often a Psync screen that is waiting on the partner refetches, in case a realtime update was missed.
private const val PSYNC_WAITING_REFETCH_MILLIS = 15_000L
