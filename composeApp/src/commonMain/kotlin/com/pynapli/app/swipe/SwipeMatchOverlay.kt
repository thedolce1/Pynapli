package com.pynapli.app.swipe

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliMotion
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.handCursor
import com.pynapli.shared.network.dto.MatchDto

@Composable
internal fun MatchOverlay(
    match: MatchDto,
    showDetails: Boolean,
    onDismiss: () -> Unit,
    onMessage: () -> Unit,
) {
    // Enter once when the overlay appears: the scrim fades in and the card settles up to full size.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = PynapliMotion.gentleSpringFloat(),
        label = "matchOverlayEnter",
    )
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress }
                .background(PynapliColors.ModalScrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .padding(32.dp)
                    .graphicsLayer {
                        val scale = 0.9f + 0.1f * progress
                        scaleX = scale
                        scaleY = scale
                    }.clip(PynapliShape.DialogShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "It's a match",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    if (showDetails) {
                        match.matchedDisplayName ?: "You both liked each other."
                    } else {
                        "You both liked each other."
                    },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onMessage,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                modifier = Modifier.handCursor(),
            ) {
                Text("Message")
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.handCursor(),
            ) {
                Text("Keep swiping")
            }
        }
    }
}
