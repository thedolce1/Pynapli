package com.pynapli.app.psync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.SwipeCardTokens
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
    const val CARD_NAME = "psync_card_name"
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
) {
    val state by stateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { stateHolder.refresh() }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(PynapliColors.Black)
                .testTag(PsyncSessionTags.SCREEN),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
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
                PsyncPhase.ACTIVE -> ActivePhase(state, stateHolder, scope)
                PsyncPhase.PAUSED ->
                    StatusPhase("Paused", "Resume when you're both back.") {
                        PrimaryButton("Resume", PsyncSessionTags.START_SESSION_BUTTON) { scope.launch { stateHolder.resume() } }
                    }
                PsyncPhase.COMPLETED -> RecapPhase(state, onDone = onBack)
                PsyncPhase.ENDED ->
                    StatusPhase("Session ended", "This Psync session is no longer active.") {
                        PrimaryButton("Done", PsyncSessionTags.START_SESSION_BUTTON, onClick = onBack)
                    }
            }
        }
    }
}

@Composable
private fun IdlePhase(onStart: () -> Unit) {
    StatusPhase(
        title = "Swipe together",
        body = "Start a Psync session and review the same people with your partner — privately, from your own phones.",
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
private fun ActivePhase(
    state: PsyncUiState,
    stateHolder: PsyncStateHolder,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val actedCount = state.deck.count { it.viewerAction != null }
    val current = state.deck.firstOrNull { it.viewerAction == null }

    Text(
        text = "$actedCount of ${state.deck.size} reviewed",
        color = PynapliColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag(PsyncSessionTags.PROGRESS),
    )
    Spacer(Modifier.height(12.dp))

    if (current == null) {
        StatusPhase(title = "You're done", body = "Waiting for your partner to finish the deck.") {
            Box(Modifier.testTag(PsyncSessionTags.WAITING))
            SecondaryButton("Nudge partner", PsyncSessionTags.NUDGE_BUTTON) { scope.launch { stateHolder.nudge() } }
        }
        return
    }

    DeckCard(current)
    Spacer(Modifier.height(20.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        CircleAction("✕", PynapliColors.Danger, PsyncSessionTags.PASS_BUTTON) {
            scope.launch { stateHolder.submitAction(current.cardToken, "pass") }
        }
        CircleAction("🍍", PynapliColors.Accent, PsyncSessionTags.PINEAPPLE_BUTTON) {
            scope.launch { stateHolder.submitAction(current.cardToken, "pineapple_like") }
        }
        CircleAction("♥", PynapliColors.Success, PsyncSessionTags.LIKE_BUTTON) {
            scope.launch { stateHolder.submitAction(current.cardToken, "like") }
        }
    }
}

@Composable
private fun DeckCard(card: PsyncDeckCardUi) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(360.dp)
                .clip(SwipeCardTokens.Shape)
                .background(PynapliColors.SurfaceRaised)
                .border(1.dp, PynapliColors.Border, SwipeCardTokens.Shape),
    ) {
        // PS-5c renders the bounded deck snapshot; the rich swipe-card carousel comes later.
        Box(modifier = Modifier.fillMaxSize()) {
            // Cover photo (deck snapshot carries no photo URL in this slice; placeholder surface).
        }
        Row(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (card.online) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(PynapliColors.Success))
                Spacer(Modifier.size(8.dp))
            }
            Text(
                text = card.displayName,
                color = PynapliColors.TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag(PsyncSessionTags.CARD_NAME),
            )
        }
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
        Text(body, color = PynapliColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
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
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.PillShape)
                .background(PynapliColors.Accent)
                .clickableTag(tag, onClick)
                .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = PynapliColors.Black, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SecondaryButton(
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.PillShape)
                .border(1.dp, PynapliColors.Border, PynapliShape.PillShape)
                .clickableTag(tag, onClick)
                .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = PynapliColors.TextPrimary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun CircleAction(
    glyph: String,
    tint: Color,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(PynapliColors.SurfaceRaised)
                .border(1.dp, tint.copy(alpha = 0.5f), CircleShape)
                .clickableTag(tag, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = tint, fontSize = 22.sp)
    }
}

private fun Modifier.clickableTag(
    tag: String,
    onClick: () -> Unit,
): Modifier = this.testTag(tag).clickable(onClick = onClick)
