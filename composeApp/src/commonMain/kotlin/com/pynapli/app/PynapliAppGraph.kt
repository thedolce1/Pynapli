package com.pynapli.app

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.pynapli.app.activity.ActivityStateHolder
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.auth.DevLoginCredentials
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.grid.GridStateHolder
import com.pynapli.app.grid.SavedGridFilterStateHolder
import com.pynapli.app.location.LocationStateHolder
import com.pynapli.app.location.TravelLocationStateHolder
import com.pynapli.app.match.MatchCelebrationStateHolder
import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.app.media.MediaAlbumsStateHolder
import com.pynapli.app.messages.MessagesStateHolder
import com.pynapli.app.onboarding.OnboardingStateHolder
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profilehub.ProfileHubStateHolder
import com.pynapli.app.profilelink.ProfileLinksStateHolder
import com.pynapli.app.psync.PsyncRepository
import com.pynapli.app.store.StoreStateHolder
import com.pynapli.app.swipe.SavedSwipeFilterStateHolder
import com.pynapli.app.swipe.SwipeStateHolder
import com.pynapli.app.tonight.PlayingTonightStateHolder
import com.pynapli.app.verification.PhotoVerificationStateHolder
import com.pynapli.shared.network.PsyncStreamClient

/**
 * The state holders and clients [PynapliApp] needs, as one object instead of one parameter each.
 *
 * [PynapliApp] previously took 29 parameters and grew by one for every feature added, which pushed
 * the same growth into `AndroidAppGraph` and `WebAppGraph` (the two platform shells that build this)
 * and into `PynapliAppEffects` / `PynapliMainContent` / `PynapliOverlays` downstream. Collapsing
 * them here means a new feature adds a property to a class — a reviewable, named place — rather than
 * widening a call signature in three files at once.
 *
 * Nullable members are genuinely optional surfaces: a platform that has not wired one (the web shell
 * omits several) passes nothing and the corresponding UI is skipped. That optionality is real, not
 * an accident of construction order.
 *
 * [Stable] rather than [Immutable]: the references never change after construction (which is what
 * Compose needs to know), but the holders themselves own mutable observable state, so claiming
 * immutability would overstate the contract.
 */
@Stable
class PynapliAppGraph(
    val authStateHolder: AuthStateHolder,
    val appSettingsStateHolder: AppSettingsStateHolder,
    val gridStateHolder: GridStateHolder,
    val profileDetailStateHolder: ProfileDetailStateHolder,
    val conversationStateHolder: ConversationStateHolder,
    val messagesStateHolder: MessagesStateHolder,
    val activityStateHolder: ActivityStateHolder,
    val locationStateHolder: LocationStateHolder,
    val mediaAlbumsStateHolder: MediaAlbumsStateHolder,
    val profileHubStateHolder: ProfileHubStateHolder,
    val profileLinksStateHolder: ProfileLinksStateHolder,
    val swipeStateHolder: SwipeStateHolder,
    val storeStateHolder: StoreStateHolder,
    val savedGridFilterStateHolder: SavedGridFilterStateHolder? = null,
    val savedSwipeFilterStateHolder: SavedSwipeFilterStateHolder? = null,
    val travelLocationStateHolder: TravelLocationStateHolder? = null,
    val playingTonightStateHolder: PlayingTonightStateHolder? = null,
    val psyncRepository: PsyncRepository? = null,
    val psyncStreamClient: PsyncStreamClient? = null,
    val deepLinkStateHolder: AppDeepLinkStateHolder? = null,
    val onboardingStateHolder: OnboardingStateHolder? = null,
    val photoVerificationStateHolder: PhotoVerificationStateHolder? = null,
    val favoritesStateHolder: com.pynapli.app.grid.FavoritesStateHolder? = null,
    /** The app-wide "It's a match" moment; absent, the Swipe screen shows its own matches. */
    val matchCelebrationStateHolder: MatchCelebrationStateHolder? = null,
    /** "Hide from people you know" (Profile > Privacy); absent, the entry is hidden. */
    val contactBlocksStateHolder: com.pynapli.app.contactblocks.ContactBlocksStateHolder? = null,
    /** Settings > Sign-in methods (Google / Apple linking); absent, the row is hidden. */
    val signInMethodsStateHolder: com.pynapli.app.profilehub.SignInMethodsStateHolder? = null,
)

/**
 * What the host platform can do and how to ask it, separated from [PynapliAppGraph] because these
 * are capabilities and callbacks rather than state.
 *
 * The split matters: the flags here are the honest way to express "this platform cannot do that"
 * (the web shell is read-only in the Store and has no launcher icon to disguise, per
 * WEB_PWA_GAMEPLAN.md W5), which previously read as loose booleans buried among 20 state holders.
 */
@Immutable
data class PynapliPlatform(
    val onUseCurrentLocation: () -> Unit,
    val onPickMediaForAlbum: (MediaAlbumKindUi) -> Unit = {},
    val onPickConversationPhoto: () -> Unit = {},
    /** Opens the photo picker for a verification selfie; null where the shell has not wired one. */
    val onPickVerificationSelfie: (() -> Unit)? = null,
    val onSetDiscreetIcon: (Boolean) -> Unit = {},
    val storePurchasesAvailable: Boolean = true,
    val discreetIconSupported: Boolean = true,
    val devLoginCredentials: DevLoginCredentials? = null,
    val testerDiagnosticLabel: String? = null,
    /**
     * Browser URL sync (WEB_PWA_GAMEPLAN.md W1.2): the web shell mirrors the canonical route into
     * the address bar; other platforms leave this null.
     */
    val onNavigationRouteChanged: ((String) -> Unit)? = null,
)
