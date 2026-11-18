package com.pynapli.app.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Verified

/*
 * Small, token-driven primitives shared across Pynapli screens. These mirror the handoff
 * prototype's `primitives.jsx` and exist so feature screens stop hardcoding visual values
 * (per docs/DESIGN_TOKENS.md: "Components consume tokens. Feature screens should not hardcode").
 */

/**
 * Online presence dot: semantic green with a ring (default the app background) so it reads on photos.
 * The dot is purely visual, so pass [contentDescription] (e.g. "Online") when the dot is the only
 * thing conveying presence inside a merged card, so a screen reader announces it.
 */
@Composable
fun OnlineDot(
    modifier: Modifier = Modifier,
    size: Dp = GridTileTokens.OnlineDotSize,
    ringColor: Color = PynapliColors.Black,
    contentDescription: String? = null,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(PynapliSemantic.Online)
                .border(1.dp, ringColor, CircleShape)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
    )
}

/** Verified check badge, tinted with the semantic verified color. */
@Composable
fun VerifiedBadge(
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    tint: Color = PynapliSemantic.Verified,
    contentDescription: String? = "Verified",
) {
    Icon(
        imageVector = Icons.Filled.Verified,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/**
 * Restrained pill / tag chip. Active state tints border + a faint fill + label with [accent];
 * inactive uses the neutral border and secondary text. Optional [leading] slot (e.g. an icon).
 *
 * When [onClick] is supplied the pill is a control, so it reserves the
 * [PynapliLayout.MinTouchTarget] 48dp interactive size — the pill's own padding only makes it ~32dp
 * tall, which is below the minimum and leaves adjacent filter chips ambiguous to tap. The
 * reservation is gated on [onClick] on purpose: a decorative tag chip (the profile peek's
 * "Together"/"Woman"/tag rows) is a label, not a target, and forcing 48dp on it would blow the
 * dense FlowRow rhythm apart for no accessibility gain. Same idiom as
 * [PynapliHeaderActionButton] — compact visual, full-size target.
 */
@Composable
fun PynapliPill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    accent: Color = PynapliColors.Accent,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val borderColor = if (selected) accent else PynapliColors.Border
    val labelColor = if (selected) accent else PynapliColors.TextSecondary
    val fill = if (selected) accent.copy(alpha = 0.08f) else Color.Transparent
    Row(
        modifier =
            modifier
                .then(if (onClick != null) Modifier.minimumInteractiveComponentSize().wideChipHeight() else Modifier)
                .clip(PynapliShape.PillShape)
                .background(fill)
                .border(1.dp, borderColor, PynapliShape.PillShape)
                .then(if (onClick != null) Modifier.handCursor().clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        leading?.invoke()
        // A pill is one word or two; wrapping split "Events" into "Even/ts" in a narrow row at large
        // text sizes. Rows of pills scroll instead.
        Text(
            text = text,
            color = labelColor,
            fontSize = PynapliType.Caption,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Bottom-up scrim so light text stays legible over a photo. Fill the photo box with this. */
@Composable
fun PhotoBottomScrim(
    modifier: Modifier = Modifier,
    heightFraction: Float = 0.55f,
    scrim: Color = PynapliColors.PhotoGradientBottom,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops =
                            arrayOf(
                                0f to Color.Transparent,
                                (1f - heightFraction) to Color.Transparent,
                                1f to scrim,
                            ),
                    ),
                ),
    )
}
