package com.pynapli.app.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.CloudOff
import com.pynapli.app.design.icons.filled.Refresh

/**
 * Canonical "designed states": a tinted icon disc, a title, an optional subtitle, and an optional
 * CTA, sharing one [PynapliStatusBlock] layout so "nothing here" ([PynapliEmptyState]) and "that
 * failed" ([PynapliErrorState]) read as siblings across the app instead of per-surface hand-rolled
 * columns / bare error text. Both fade and lift in gently via [PynapliMotion].
 */
@Composable
fun PynapliEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    PynapliStatusBlock(icon = icon, title = title, modifier = modifier, subtitle = subtitle) {
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(text = actionLabel)
            }
        }
    }
}

/**
 * Canonical failure state: same block as [PynapliEmptyState] but with an error icon and a Retry CTA.
 * Use it for full-surface load failures (Grid / Swipe / Activity / Messages) so retry affordances are
 * consistent rather than a bare red server string.
 */
@Composable
fun PynapliErrorState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector = Icons.Filled.CloudOff,
    retryLabel: String = "Retry",
    onRetry: (() -> Unit)? = null,
) {
    PynapliStatusBlock(icon = icon, title = title, modifier = modifier, subtitle = message) {
        if (onRetry != null) {
            Button(
                onClick = onRetry,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(text = retryLabel, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun PynapliStatusBlock(
    icon: ImageVector,
    title: String,
    modifier: Modifier,
    subtitle: String?,
    action: @Composable ColumnScope.() -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = PynapliMotion.standardTween(durationMillis = PynapliMotion.SLOW),
        label = "statusBlockEnter",
    )

    Box(
        modifier = modifier.padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier.graphicsLayer {
                    alpha = progress
                    translationY = (1f - progress) * 12.dp.toPx()
                },
        ) {
            Box(
                modifier =
                    Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp),
            )
            subtitle?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            action()
        }
    }
}
