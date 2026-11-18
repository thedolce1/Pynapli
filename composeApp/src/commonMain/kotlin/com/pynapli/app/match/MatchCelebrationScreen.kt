package com.pynapli.app.match

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliMotion
import com.pynapli.app.design.closeOnEscape
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.rememberPynapliHaptics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object MatchCelebrationTags {
    const val ROOT = "match-celebration"
    const val TITLE = "match-celebration-title"
    const val SUBTITLE = "match-celebration-subtitle"
    const val MESSAGE = "match-celebration-message"
    const val KEEP_SWIPING = "match-celebration-keep-swiping"
    const val PAIR_PHOTO = "match-celebration-pair-photo"
}

/**
 * The full-screen "It's a match" moment (SWIPE_IMPLEMENTATION_SPEC "Match moment"): both photos,
 * the gold title, "Send a message" and "Keep swiping". Not a sheet: a takeover over the whole app
 * that swallows touches and keys so nothing underneath (the deck) moves while it is up. A Psync
 * together match shows the pair ("Maya & Alex") the way Activity does.
 *
 * [showNames] follows the notification-detail setting: discreet keeps names off this screen too.
 */
@Composable
fun MatchCelebrationScreen(
    celebration: MatchCelebrationUi,
    showNames: Boolean,
    onSendMessage: () -> Unit,
    onKeepSwiping: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberPynapliHaptics()
    // Enter: scrim fades in, then the photos settle in from the sides, then the copy rises.
    val scrim = remember(celebration.primaryMatchId) { Animatable(0f) }
    val photos = remember(celebration.primaryMatchId) { Animatable(0f) }
    val copy = remember(celebration.primaryMatchId) { Animatable(0f) }
    LaunchedEffect(celebration.primaryMatchId) {
        haptics.matchSuccess()
        coroutineScope {
            launch { scrim.animateTo(1f, PynapliMotion.standardTween(PynapliMotion.STANDARD)) }
            launch { photos.animateTo(1f, PynapliMotion.popSpringFloat()) }
            launch {
                delay(PynapliMotion.FAST.toLong())
                copy.animateTo(1f, PynapliMotion.gentleSpringFloat())
            }
        }
    }

    val matchedName = celebration.matchedDisplayName?.takeIf { showNames && it.isNotBlank() }
    val pairName = celebration.pairDisplayName?.takeIf { showNames && it.isNotBlank() }
    val subtitle =
        when {
            celebration.isPair && matchedName != null && pairName != null -> "$matchedName & $pairName liked you together."
            celebration.isPair -> "A couple liked you together, and you liked them back."
            matchedName != null -> "You and $matchedName liked each other."
            else -> "You both liked each other."
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(MatchCelebrationTags.ROOT)
                .graphicsLayer { alpha = scrim.value }
                .background(PynapliColors.Black.copy(alpha = 0.94f))
                // Swallow every touch, click and drag so the deck underneath cannot move.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                }.closeOnEscape(onKeepSwiping)
                .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "It's a match",
                color = PynapliColors.Accent,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .testTag(MatchCelebrationTags.TITLE)
                        .semantics { heading() }
                        .graphicsLayer {
                            alpha = copy.value
                            translationY = (1f - copy.value) * 16.dp.toPx()
                        },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = subtitle,
                color = PynapliColors.TextSecondary,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .testTag(MatchCelebrationTags.SUBTITLE)
                        .graphicsLayer { alpha = copy.value },
            )
            Spacer(Modifier.height(36.dp))
            MatchPhotos(celebration = celebration, progress = { photos.value })
            Spacer(Modifier.height(44.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = copy.value
                            translationY = (1f - copy.value) * 24.dp.toPx()
                        },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onSendMessage,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = PynapliColors.Accent,
                            contentColor = PynapliColors.TextInverse,
                        ),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .handCursor()
                            .testTag(MatchCelebrationTags.MESSAGE),
                ) {
                    Text("Send a message", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onKeepSwiping,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .handCursor()
                            .testTag(MatchCelebrationTags.KEEP_SWIPING),
                ) {
                    Text("Keep swiping", color = PynapliColors.TextPrimary, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@Composable
private fun MatchPhotos(
    celebration: MatchCelebrationUi,
    progress: () -> Float,
) {
    Box(contentAlignment = Alignment.Center) {
        // The viewer on the left, tilted out; the match on the right, tilted in: two cards meeting.
        MatchPhoto(
            url = celebration.viewerPhotoUrl,
            initial = null,
            size = PhotoSize,
            modifier =
                Modifier
                    .offset(x = -PhotoOverlap)
                    .graphicsLayer {
                        val p = progress()
                        translationX = (1f - p) * -140.dp.toPx()
                        rotationZ = -8f * p
                        alpha = p.coerceIn(0f, 1f)
                    },
        )
        Box(
            modifier =
                Modifier
                    .offset(x = PhotoOverlap)
                    .graphicsLayer {
                        val p = progress()
                        translationX = (1f - p) * 140.dp.toPx()
                        rotationZ = 8f * p
                        alpha = p.coerceIn(0f, 1f)
                    },
        ) {
            MatchPhoto(url = celebration.matchedPhotoUrl, initial = celebration.matchedDisplayName, size = PhotoSize)
            if (celebration.isPair) {
                MatchPhoto(
                    url = celebration.pairPhotoUrl,
                    initial = celebration.pairDisplayName,
                    size = PairPhotoSize,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 18.dp, y = 14.dp)
                            .testTag(MatchCelebrationTags.PAIR_PHOTO),
                )
            }
        }
    }
}

@Composable
private fun MatchPhoto(
    url: String?,
    initial: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(PynapliColors.SurfaceRaised)
                .border(3.dp, PynapliColors.Accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text =
                    initial
                        ?.trim()
                        ?.firstOrNull()
                        ?.uppercase() ?: "",
                color = PynapliColors.TextSecondary,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private val PhotoSize = 132.dp
private val PairPhotoSize = 58.dp
private val PhotoOverlap = 52.dp
