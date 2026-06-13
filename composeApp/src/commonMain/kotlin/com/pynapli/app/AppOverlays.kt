package com.pynapli.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.conversation.ConversationSheet
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.messages.MessageProfilePeekSheet
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profilelink.ProfileLinksStateHolder
import com.pynapli.app.psync.PsyncSessionScreen
import com.pynapli.app.psync.PsyncStateHolder
import com.pynapli.app.tonight.PlayingTonightStateHolder
import com.pynapli.shared.network.PsyncStreamClient
import kotlinx.coroutines.CoroutineScope
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
    scope: CoroutineScope,
) {
    val authState by authStateHolder.state.collectAsState()
    val conversationState by conversationStateHolder.state.collectAsState()
    val profileDetailState by profileDetailStateHolder.state.collectAsState()
    val profileLinksState by profileLinksStateHolder.state.collectAsState()
    val appSettingsState by appSettingsStateHolder.state.collectAsState()
    val conversationShownInline = selectedTab == PynapliTab.Messages && conversationState.visible

    if (tonightVisible && playingTonightStateHolder != null) {
        // System back (gesture/hardware) closes the full-screen overlay instead of falling
        // through to the Activity — otherwise the only exit is the small top-left back button.
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
            modifier = Modifier.fillMaxSize(),
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
                client.connect().collect { psyncStateHolder.refresh() }
            }
        }
        PsyncSessionScreen(
            stateHolder = psyncStateHolder,
            onBack = onPsyncDismiss,
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (selectedTab == PynapliTab.Messages && profileDetailState.visible) {
        MessageProfilePeekSheet(
            state = profileDetailState,
            onDismiss = profileDetailStateHolder::dismiss,
            onMessage = profileDetailStateHolder::dismiss,
            onSpark = {
                scope.launch { profileDetailStateHolder.sendSpark() }
            },
            onRequestAlbum = {
                scope.launch { profileDetailStateHolder.requestAlbumAccess() }
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
        )
    }
}
