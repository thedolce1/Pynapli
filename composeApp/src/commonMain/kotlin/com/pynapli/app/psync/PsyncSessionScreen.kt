package com.pynapli.app.psync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pynapli.app.design.PynapliButton
import com.pynapli.app.design.PynapliButtonStyle
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliContentWidth
import com.pynapli.app.design.PynapliMaxWidthColumn
import com.pynapli.app.design.PynapliPill
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.SwipeCardTokens
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.automirrored.filled.ArrowBack
import com.pynapli.app.design.icons.filled.Close
import com.pynapli.app.design.icons.filled.Favorite
import com.pynapli.app.design.icons.filled.Star
import com.pynapli.app.swipe.SwipeActionButton
import com.pynapli.app.swipe.SwipeCardStack
import com.pynapli.app.swipe.SwipeFilters
import com.pynapli.app.swipe.SwipeFiltersSheet
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.pynapli.shared.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Stable test tags for the Psync session surface (PS-5c). */
object PsyncSessionTags {
    const val SCREEN = "psync_session_screen"
    const val START_PSYNC_BUTTON = "psync_start_button"
    const val ACCEPT_BUTTON = "psync_accept_button"
    const val DECLINE_BUTTON = "psync_decline_button"
    const val START_SESSION_BUTTON = "psync_start_session_button"
    const val PASS_BUTTON = "psync_pass_button"
    const val LIKE_BUTTON = "psync_like_button"
    const val PINEAPPLE_BUTTON = "psync_pineapple_button"
    const val NUDGE_BUTTON = "psync_nudge_button"
    const val PROGRESS = "psync_progress"
    const val RECAP = "psync_recap"
    const val WAITING = "psync_waiting"
    const val PAUSE_BUTTON = "psync_pause_button"
    const val END_SESSION_BUTTON = "psync_end_session_button"
    const val CONFIRM_END_BUTTON = "psync_confirm_end_button"
    const val KEEP_GOING_BUTTON = "psync_keep_going_button"
    const val FINISH_NOW_BUTTON = "psync_finish_now_button"
    const val NOTICE = "psync_notice"
    const val SEE_PLANS_BUTTON = "psync_see_plans_button"
    const val LINK_PARTNER_BUTTON = "psync_link_partner_button"
    const val START_ANOTHER_BUTTON = "psync_start_another_button"
    const val ENDED_EARLY = "psync_ended_early"

    fun togetherMatchRow(userId: String) = "psync_together_match_$userId"

    const val PARTNER_FINISHED = "psync_partner_finished"
    const val SETUP_SUMMARY = "psync_setup_summary"
    const val EDIT_FILTERS_BUTTON = "psync_edit_filters_button"
    const val SUGGEST_CHANGES_BUTTON = "psync_suggest_changes_button"

    fun deckSizeChip(size: Int) = "psync_deck_size_$size"
}

private const val PSYNC_NO_LINKED_PARTNER = "PSYNC_NO_LINKED_PARTNER"

/** Refusals that a higher tier lifts; the screen offers the Store next to them. */
private val PSYNC_UPGRADE_CODES =
    setOf(
        "PSYNC_DECK_SIZE_NOT_ALLOWED",
        "PSYNC_ADVANCED_FILTERS_NOT_ALLOWED",
        "PSYNC_SESSION_LIMIT_REACHED",
        "PINEAPPLE_LIKE_INVENTORY_REQUIRED",
    )

/** Deck sizes offered at setup. The server caps them by tier (Free 10, Social 25, Select 50). */
private val PSYNC_DECK_SIZES = listOf(10, 25, 50)

/**
 * The Psync synchronized-swipe surface (PS-5c, PSYNC_SPEC). A single phase-driven screen over
 * [PsyncStateHolder]: invite/accept → ready/start → the shared deck (the viewer swipes privately)
 * → recap. The server is the authority; this only renders [PsyncUiState] and fires callbacks.
 */
@Composable
fun PsyncSessionScreen(
    stateHolder: PsyncStateHolder,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    distanceUnit: String = AppSettings.UNIT_METRIC,
    onOpenProfile: (SwipeCardDto) -> Unit = {},
    onOpenProfileById: (userId: String, displayName: String?) -> Unit = { _, _ -> },
    onOpenStore: () -> Unit = {},
    onOpenProfileHub: () -> Unit = {},
) {
    val state by stateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { stateHolder.refresh() }

    // Capped like the Swipe stage: on a tablet or desktop the deck card, buttons and recap read as
    // one centered column instead of a phone layout stretched edge to edge. A no-op on phones.
    PynapliMaxWidthColumn(
        maxWidth = PynapliContentWidth.Stage,
        modifier = modifier.testTag(PsyncSessionTags.SCREEN),
        background = PynapliColors.Black,
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
            PsyncHeader(title = "Psync", onBack = onBack)
            Spacer(Modifier.height(16.dp))

            val needsLinkedPartner = state.errorCode == PSYNC_NO_LINKED_PARTNER
            state.errorMessage?.takeUnless { needsLinkedPartner }?.let { message ->
                ErrorBanner(message)
                if (state.errorCode in PSYNC_UPGRADE_CODES) {
                    // A tier limit is a reason to upgrade, not a dead end.
                    Spacer(Modifier.height(8.dp))
                    PynapliPill(
                        text = "See plans",
                        selected = true,
                        onClick = onOpenStore,
                        modifier = Modifier.testTag(PsyncSessionTags.SEE_PLANS_BUTTON),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            state.notice?.let { message ->
                NoticeBanner(message)
                Spacer(Modifier.height(12.dp))
                // A notice is a passing remark; it clears itself.
                LaunchedEffect(message) {
                    delay(NOTICE_VISIBLE_MILLIS)
                    stateHolder.clearNotice()
                }
            }

            when (state.phase) {
                PsyncPhase.IDLE ->
                    if (needsLinkedPartner) {
                        StatusPhase(
                            title = "Link your partner first",
                            body = "Psync is for linked partners. Link profiles with your partner, then start a session together.",
                        ) {
                            PrimaryButton("Go to my profile", PsyncSessionTags.LINK_PARTNER_BUTTON) {
                                stateHolder.clearError()
                                onOpenProfileHub()
                            }
                        }
                    } else {
                        SetupPhase(
                            distanceUnit = distanceUnit,
                            onInvite = { deckSize, filters -> scope.launch { stateHolder.createSession(deckSize, filters.toSwipeDeckRequest()) } },
                        )
                    }
                PsyncPhase.INVITED, PsyncPhase.CONFIGURING -> NegotiationPhase(state, stateHolder, scope, distanceUnit)
                PsyncPhase.READY ->
                    ReadyPhase(
                        state,
                        onStart = { scope.launch { stateHolder.start() } },
                        onCancel = { scope.launch { stateHolder.leave() } },
                    )
                PsyncPhase.ACTIVE -> ActivePhase(state, stateHolder, scope, distanceUnit, onOpenProfile)
                PsyncPhase.PAUSED ->
                    StatusPhase("Paused", "Your places are saved. Resume when you're both back.") {
                        PrimaryButton("Resume", PsyncSessionTags.START_SESSION_BUTTON) { scope.launch { stateHolder.resume() } }
                        Spacer(Modifier.height(10.dp))
                        EndSessionControl(onEnd = { scope.launch { stateHolder.leave() } })
                    }
                PsyncPhase.COMPLETED ->
                    RecapPhase(
                        state,
                        onOpenMatch = onOpenProfileById,
                        onStartAnother = stateHolder::startAnother,
                        onDone = {
                            stateHolder.dismissFinished()
                            onBack()
                        },
                    )
                PsyncPhase.ENDED ->
                    StatusPhase("Session ended", "This Psync session is no longer active.") {
                        PrimaryButton("Start a new session", PsyncSessionTags.START_ANOTHER_BUTTON, onClick = stateHolder::startAnother)
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton("Done", PsyncSessionTags.START_SESSION_BUTTON) {
                            stateHolder.dismissFinished()
                            onBack()
                        }
                    }
            }
        }
    }
}

/**
 * Before inviting: the initiator picks what the two of them will review. Nothing is sent until
 * they tap Invite, and the partner sees this same setup before agreeing to it.
 */
@Composable
private fun SetupPhase(
    distanceUnit: String,
    onInvite: (deckSize: Int, filters: SwipeFilters) -> Unit,
) {
    var deckSize by rememberSaveable { mutableStateOf(PSYNC_DECK_SIZES[1]) }
    var filters by remember { mutableStateOf(SwipeFilters.defaults) }
    var editingFilters by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(24.dp))
        Text("Swipe together", color = PynapliColors.TextPrimary, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Review the same people with your partner, each on your own device. Your swipes stay private.",
            color = PynapliColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))

        SetupLabel("Deck size")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PSYNC_DECK_SIZES.forEach { size ->
                PynapliPill(
                    text = "$size",
                    selected = size == deckSize,
                    onClick = { deckSize = size },
                    modifier = Modifier.testTag(PsyncSessionTags.deckSizeChip(size)),
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        SetupLabel("Who you'll see")
        SetupSummaryCard(lines = filters.toSwipeDeckRequest().toSetupLines(distanceUnit))
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Change filters", PsyncSessionTags.EDIT_FILTERS_BUTTON) { editingFilters = true }

        Spacer(Modifier.height(24.dp))
        PrimaryButton("Invite partner", PsyncSessionTags.START_PSYNC_BUTTON) { onInvite(deckSize, filters) }
    }

    if (editingFilters) {
        SwipeFiltersSheet(
            initial = filters,
            onDismiss = { editingFilters = false },
            onApply = { updated ->
                filters = updated
                editingFilters = false
            },
            onClearAll = {
                filters = SwipeFilters.defaults
                editingFilters = false
            },
        )
    }
}

/**
 * The setup is on the table and someone has to answer. Both partners see the same summary, so
 * nobody agrees to a session they can't see (PSYNC_SPEC "Partner Review" / "Filter Agreement").
 */
@Composable
private fun NegotiationPhase(
    state: PsyncUiState,
    stateHolder: PsyncStateHolder,
    scope: kotlinx.coroutines.CoroutineScope,
    distanceUnit: String,
) {
    val session = state.session ?: return
    val partner = session.partnerDisplayName ?: "Your partner"
    var suggesting by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(24.dp))
        val title =
            when {
                session.partnerSuggestedChanges -> "$partner suggested changes"
                session.awaitingViewerApproval -> "$partner invited you to Psync"
                else -> "Waiting for $partner"
            }
        val body =
            when {
                session.partnerSuggestedChanges -> "Here's the updated setup. You both need to agree before it starts."
                session.awaitingViewerApproval -> "You'll review the same deck. Swipes stay private."
                else -> "They'll see this setup and can accept, suggest changes or decline."
            }
        Text(title, color = PynapliColors.TextPrimary, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(body, color = PynapliColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))

        SetupSummaryCard(lines = session.setupSummary(distanceUnit))
        Spacer(Modifier.height(20.dp))

        if (session.awaitingViewerApproval) {
            PrimaryButton("Accept", PsyncSessionTags.ACCEPT_BUTTON) { scope.launch { stateHolder.accept() } }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Suggest changes", PsyncSessionTags.SUGGEST_CHANGES_BUTTON) { suggesting = true }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Decline", PsyncSessionTags.DECLINE_BUTTON) { scope.launch { stateHolder.deny() } }
        } else {
            SecondaryButton("Change filters", PsyncSessionTags.SUGGEST_CHANGES_BUTTON) { suggesting = true }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Cancel", PsyncSessionTags.DECLINE_BUTTON) { scope.launch { stateHolder.leave() } }
        }
    }

    if (suggesting) {
        SwipeFiltersSheet(
            initial = SwipeFilters.fromSwipeDeckRequest(session.filters),
            onDismiss = { suggesting = false },
            onApply = { updated ->
                suggesting = false
                scope.launch { stateHolder.suggestFilters(updated.toSwipeDeckRequest()) }
            },
            onClearAll = {
                suggesting = false
                scope.launch { stateHolder.suggestFilters(SwipeFilters.defaults.toSwipeDeckRequest()) }
            },
        )
    }
}

@Composable
private fun SetupLabel(text: String) {
    Text(
        text = text,
        color = PynapliColors.TextSecondary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** The agreed (or proposed) setup, one fact per line. */
@Composable
private fun SetupSummaryCard(lines: List<String>) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .background(PynapliColors.Surface)
                .border(1.dp, PynapliColors.Border, PynapliShape.CardShape)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag(PsyncSessionTags.SETUP_SUMMARY),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lines.forEach { line ->
            Text(line, color = PynapliColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The filter half of [PsyncSessionUi.setupSummary], for the setup step before a session exists. */
private fun SwipeDeckRequestDto.toSetupLines(distanceUnit: String): List<String> =
    PsyncSessionUi(
        id = "",
        status = "",
        deckSize = 0,
        isInitiator = true,
        partnerDisplayName = null,
        viewerApproved = true,
        partnerApproved = false,
        filters = this,
    ).setupSummary(distanceUnit).drop(1)

@Composable
private fun ReadyPhase(
    state: PsyncUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val session = state.session
    val partner = session?.partnerDisplayName ?: "your partner"
    if (session?.viewerStarted == true) {
        // The viewer has tapped Start; say so instead of offering the same button again.
        StatusPhase(title = "Waiting for $partner", body = "The deck appears as soon as they tap Start too.") {
            Box(Modifier.testTag(PsyncSessionTags.WAITING))
            SecondaryButton("Cancel", PsyncSessionTags.DECLINE_BUTTON, onClick = onCancel)
        }
    } else {
        val body =
            if (session?.partnerStarted == true) {
                "${partner.replaceFirstChar { it.uppercase() }} is ready. Tap Start to see the deck."
            } else {
                "Tap Start. The deck appears once you and $partner have both started."
            }
        StatusPhase(title = "You're both in", body = body) {
            PrimaryButton("Start", PsyncSessionTags.START_SESSION_BUTTON, onClick = onStart)
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Cancel", PsyncSessionTags.DECLINE_BUTTON, onClick = onCancel)
        }
    }
}

@Composable
private fun ColumnScope.ActivePhase(
    state: PsyncUiState,
    stateHolder: PsyncStateHolder,
    scope: kotlinx.coroutines.CoroutineScope,
    distanceUnit: String,
    onOpenProfile: (SwipeCardDto) -> Unit,
) {
    val remaining = state.remainingCards
    val actedCount = state.deck.size - remaining.size

    val partner = state.session?.partnerDisplayName ?: "Your partner"
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$actedCount of ${state.deck.size} reviewed",
                color = PynapliColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(PsyncSessionTags.PROGRESS),
            )
            if (state.partnerFinished && remaining.isNotEmpty()) {
                // Only that they are through the deck, never which way they swiped.
                Text(
                    text = "$partner has finished",
                    color = PynapliColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(PsyncSessionTags.PARTNER_FINISHED),
                )
            }
        }
        if (remaining.isNotEmpty()) {
            PynapliPill(
                text = "Pause",
                onClick = { scope.launch { stateHolder.pause() } },
                modifier = Modifier.testTag(PsyncSessionTags.PAUSE_BUTTON),
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    if (remaining.isEmpty()) {
        StatusPhase(title = "You're done", body = "Waiting for $partner to finish the deck.") {
            Box(Modifier.testTag(PsyncSessionTags.WAITING))
            SecondaryButton("Nudge", PsyncSessionTags.NUDGE_BUTTON) { scope.launch { stateHolder.nudge() } }
            Spacer(Modifier.height(10.dp))
            // Don't hold the viewer hostage to a partner who has wandered off: what both have
            // decided so far is resolved now.
            SecondaryButton("See results now", PsyncSessionTags.FINISH_NOW_BUTTON) { scope.launch { stateHolder.leave() } }
        }
        return
    }

    // The same card stack as the Swipe deck: photos, drag-to-decide, tap for the profile. The
    // button taps go through `requestedAction` so the top card flies out before the decision lands.
    var requestedAction by remember { mutableStateOf<String?>(null) }
    val cards = remember(remaining) { remaining.map { it.card } }
    val tokens = remember(remaining) { remaining.associate { it.userId to it.cardToken } }

    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        SwipeCardStack(
            cards = cards,
            enabled = true,
            requestedAction = requestedAction,
            distanceUnit = distanceUnit,
            onActionConsumed = { requestedAction = null },
            onOpenProfile = onOpenProfile,
            // A linked partner on a card is context, not a second profile to open from here.
            onLinkedPartnerTap = onOpenProfile,
            onSwiped = { card, action ->
                tokens[card.userId]?.let { token -> scope.launch { stateHolder.submitAction(token, action) } }
            },
        )
    }
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SwipeActionButton(
            enabled = true,
            isSubmitting = false,
            testTag = PsyncSessionTags.PASS_BUTTON,
            icon = Icons.Filled.Close,
            contentDescription = "Pass",
            background = PynapliColors.Danger.copy(alpha = 0.20f),
            iconTint = PynapliColors.Danger,
            onClick = { requestedAction = SwipeAction.PASS },
        )
        // The only gold button, as in the Swipe deck: Pineapple Like is the premium action.
        SwipeActionButton(
            enabled = true,
            isSubmitting = false,
            testTag = PsyncSessionTags.PINEAPPLE_BUTTON,
            icon = Icons.Filled.Star,
            contentDescription = "Pineapple Like",
            background = PynapliColors.Accent.copy(alpha = 0.20f),
            iconTint = PynapliColors.Accent,
            onClick = { requestedAction = SwipeAction.PINEAPPLE_LIKE },
        )
        SwipeActionButton(
            enabled = true,
            isSubmitting = false,
            testTag = PsyncSessionTags.LIKE_BUTTON,
            icon = Icons.Filled.Favorite,
            contentDescription = "Like",
            background = PynapliColors.Success.copy(alpha = 0.20f),
            iconTint = PynapliColors.Success,
            onClick = { requestedAction = SwipeAction.LIKE },
        )
    }
    Spacer(Modifier.height(8.dp))
    EndSessionControl(onEnd = { scope.launch { stateHolder.leave() } })
}

/**
 * Ending is always available and never paywalled (PSYNC_SPEC). It ends the session for both
 * partners, so it asks once; what each has decided so far still counts.
 */
@Composable
private fun EndSessionControl(onEnd: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    if (!confirming) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = "End session",
                color = PynapliColors.TextSecondary,
                style = MaterialTheme.typography.labelLarge,
                modifier =
                    Modifier
                        .clip(PynapliShape.PillShape)
                        .clickableTag(PsyncSessionTags.END_SESSION_BUTTON) { confirming = true }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "End for both of you? Your swipes so far still count.",
                color = PynapliColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PynapliPill(
                    text = "Keep going",
                    onClick = { confirming = false },
                    modifier = Modifier.testTag(PsyncSessionTags.KEEP_GOING_BUTTON),
                )
                PynapliPill(
                    text = "End session",
                    selected = true,
                    accent = PynapliColors.Danger,
                    onClick = {
                        confirming = false
                        onEnd()
                    },
                    modifier = Modifier.testTag(PsyncSessionTags.CONFIRM_END_BUTTON),
                )
            }
        }
    }
}

@Composable
private fun RecapPhase(
    state: PsyncUiState,
    onOpenMatch: (userId: String, displayName: String?) -> Unit,
    onStartAnother: () -> Unit,
    onDone: () -> Unit,
) {
    val recap = state.recap
    val deckSize = state.session?.deckSize ?: 0
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(PsyncSessionTags.RECAP)) {
        Text("Psync recap", color = PynapliColors.TextPrimary, style = MaterialTheme.typography.headlineSmall)
        if (recap != null && deckSize > 0 && recap.profilesReviewed < deckSize) {
            // Restrained on purpose (PSYNC_SPEC): it ended early, not who ended it or why.
            Text(
                text = "Ended after ${recap.profilesReviewed} of $deckSize profiles",
                color = PynapliColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp).testTag(PsyncSessionTags.ENDED_EARLY),
            )
        }
        Spacer(Modifier.height(16.dp))
        if (recap == null) {
            Text("Your recap is on the way.", color = PynapliColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        } else {
            RecapRow("Profiles you reviewed", recap.profilesReviewed)
            RecapRow("Both liked", recap.bothLikedCount)
            RecapRow("Together matches", recap.togetherMatchCount)
            RecapRow("Your solo matches", recap.soloMatchCount)
            recap.partnerSoloMatchCount?.let { RecapRow("Their solo matches", it) }
            if (recap.togetherMatches.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Together matches", color = PynapliColors.TextSecondary, style = MaterialTheme.typography.titleSmall)
                recap.togetherMatches.forEach { match ->
                    // Opens the profile, where messaging follows the normal chat-consent rules.
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .clip(PynapliShape.CardShape)
                                .clickableTag(PsyncSessionTags.togetherMatchRow(match.userId)) { onOpenMatch(match.userId, match.displayName) }
                                .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = match.displayName ?: "Pynapli User",
                            color = PynapliColors.TextPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text("View", color = PynapliColors.Accent, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (recap.filtersSummary.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(recap.filtersSummary, color = PynapliColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Start another session", PsyncSessionTags.START_ANOTHER_BUTTON, onClick = onStartAnother)
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Done", PsyncSessionTags.START_SESSION_BUTTON, onClick = onDone)
    }
}

@Composable
private fun RecapRow(
    label: String,
    value: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = PynapliColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(
            "$value",
            color = PynapliColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatusPhase(
    title: String,
    body: String,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Text(title, color = PynapliColors.TextPrimary, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(body, color = PynapliColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        actions()
    }
}

@Composable
private fun PsyncHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(PynapliColors.Surface)
                    .clickableTag("psync_back") { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PynapliColors.TextPrimary)
        }
        Spacer(Modifier.size(12.dp))
        Text(title, color = PynapliColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
    }
}

/** A passing, non-error remark ("Nudge sent."). */
@Composable
private fun NoticeBanner(message: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .background(PynapliColors.Surface)
                .border(1.dp, PynapliColors.Border, PynapliShape.CardShape)
                .padding(12.dp)
                .testTag(PsyncSessionTags.NOTICE),
    ) {
        Text(message, color = PynapliColors.TextPrimary, style = MaterialTheme.typography.bodySmall)
    }
}

private const val NOTICE_VISIBLE_MILLIS = 4_000L

@Composable
private fun ErrorBanner(message: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .background(PynapliColors.Danger.copy(alpha = 0.12f))
                .padding(12.dp),
    ) {
        Text(message, color = PynapliColors.Danger, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    tag: String,
    onClick: () -> Unit,
) = PynapliButton(label, onClick, Modifier.fillMaxWidth().testTag(tag))

@Composable
private fun SecondaryButton(
    label: String,
    tag: String,
    onClick: () -> Unit,
) = PynapliButton(label, onClick, Modifier.fillMaxWidth().testTag(tag), style = PynapliButtonStyle.Secondary)

private fun Modifier.clickableTag(
    tag: String,
    onClick: () -> Unit,
): Modifier = this.testTag(tag).handCursor().clickable(onClick = onClick)
