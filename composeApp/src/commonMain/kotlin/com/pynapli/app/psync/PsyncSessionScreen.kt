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
import androidx.compose.foundation.shape.CircleShape
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
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.settings.AppSettings
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
}

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

            state.errorMessage?.let { message ->
                ErrorBanner(message)
                Spacer(Modifier.height(12.dp))
            }

            when (state.phase) {
                PsyncPhase.IDLE -> IdlePhase(onStart = { scope.launch { stateHolder.createSession() } })
                PsyncPhase.INVITED, PsyncPhase.CONFIGURING -> NegotiationPhase(state, stateHolder, scope)
                PsyncPhase.READY -> ReadyPhase(state, onStart = { scope.launch { stateHolder.start() } })
                PsyncPhase.ACTIVE -> ActivePhase(state, stateHolder, scope, distanceUnit, onOpenProfile)
                PsyncPhase.PAUSED ->
                    StatusPhase("Paused", "Resume when you're both back.") {
                        PrimaryButton("Resume", PsyncSessionTags.START_SESSION_BUTTON) { scope.launch { stateHolder.resume() } }
                    }
                PsyncPhase.COMPLETED ->
                    RecapPhase(
                        state,
                        onDone = {
                            stateHolder.dismissFinished()
                            onBack()
                        },
                    )
                PsyncPhase.ENDED ->
                    StatusPhase("Session ended", "This Psync session is no longer active.") {
                        PrimaryButton("Done", PsyncSessionTags.START_SESSION_BUTTON) {
                            stateHolder.dismissFinished()
                            onBack()
                        }
                    }
            }
        }
    }
}

@Composable
private fun IdlePhase(onStart: () -> Unit) {
    StatusPhase(
        title = "Swipe together",
        body = "Start a Psync session and review the same people with your partner — privately, each on your own device.",
    ) {
        PrimaryButton("Start Psync", PsyncSessionTags.START_PSYNC_BUTTON, onClick = onStart)
    }
}

@Composable
private fun NegotiationPhase(
    state: PsyncUiState,
    stateHolder: PsyncStateHolder,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val session = state.session ?: return
    val partner = session.partnerDisplayName ?: "Your partner"
    if (session.awaitingViewerApproval) {
        StatusPhase(title = "$partner invited you to Psync", body = "Review the same deck together. Swipes stay private.") {
            PrimaryButton("Accept", PsyncSessionTags.ACCEPT_BUTTON) { scope.launch { stateHolder.accept() } }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Decline", PsyncSessionTags.DECLINE_BUTTON) { scope.launch { stateHolder.deny() } }
        }
    } else {
        StatusPhase(title = "Waiting for $partner", body = "We'll start as soon as they accept.") {
            SecondaryButton("Cancel", PsyncSessionTags.DECLINE_BUTTON) { scope.launch { stateHolder.leave() } }
        }
    }
}

@Composable
private fun ReadyPhase(
    state: PsyncUiState,
    onStart: () -> Unit,
) {
    val partner = state.session?.partnerDisplayName ?: "your partner"
    StatusPhase(title = "You're both in", body = "Tap Start. The shared deck appears once you and $partner have both started.") {
        PrimaryButton("Start", PsyncSessionTags.START_SESSION_BUTTON, onClick = onStart)
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

    Text(
        text = "$actedCount of ${state.deck.size} reviewed",
        color = PynapliColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag(PsyncSessionTags.PROGRESS),
    )
    Spacer(Modifier.height(12.dp))

    if (remaining.isEmpty()) {
        StatusPhase(title = "You're done", body = "Waiting for your partner to finish the deck.") {
            Box(Modifier.testTag(PsyncSessionTags.WAITING))
            SecondaryButton("Nudge partner", PsyncSessionTags.NUDGE_BUTTON) { scope.launch { stateHolder.nudge() } }
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
}

@Composable
private fun RecapPhase(
    state: PsyncUiState,
    onDone: () -> Unit,
) {
    val recap = state.recap
    Column(modifier = Modifier.fillMaxWidth().testTag(PsyncSessionTags.RECAP)) {
        Text("Psync recap", color = PynapliColors.TextPrimary, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        if (recap == null) {
            Text("Your recap is on the way.", color = PynapliColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        } else {
            RecapRow("Profiles reviewed", recap.profilesReviewed)
            RecapRow("Both liked", recap.bothLikedCount)
            RecapRow("Together matches", recap.togetherMatchCount)
            RecapRow("Your solo matches", recap.soloMatchCount)
            if (recap.togetherMatches.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Together matches", color = PynapliColors.TextSecondary, style = MaterialTheme.typography.titleSmall)
                recap.togetherMatches.forEach { match ->
                    Text(
                        text = match.displayName ?: "Pynapli User",
                        color = PynapliColors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (recap.filtersSummary.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(recap.filtersSummary, color = PynapliColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Done", PsyncSessionTags.START_SESSION_BUTTON, onClick = onDone)
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
