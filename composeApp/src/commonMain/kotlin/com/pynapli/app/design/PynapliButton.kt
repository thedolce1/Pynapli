package com.pynapli.app.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** [Primary] is the one gold action on a surface; [Secondary] is the quiet outlined alternative. */
enum class PynapliButtonStyle { Primary, Secondary }

/** [Large] is a full-width call to action; [Compact] sits inline in a row or a floating card. */
enum class PynapliButtonSize { Large, Compact }

/**
 * Pill-shaped text button. Replaces the per-feature copies (Tonight's PillButton, Psync's
 * Primary/SecondaryButton) so actions look the same everywhere. Keep at most one [Primary] per
 * surface: gold is a highlight, not the whole palette. For filter/toggle chips use [PynapliPill].
 *
 * Always reserves the 48dp minimum touch target, even when the compact visual is ~32dp tall.
 */
@Composable
fun PynapliButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PynapliButtonStyle = PynapliButtonStyle.Primary,
    size: PynapliButtonSize = PynapliButtonSize.Large,
) {
    val primary = style == PynapliButtonStyle.Primary
    Box(
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .clip(PynapliShape.PillShape)
                .background(if (primary) PynapliColors.Accent else PynapliColors.Surface)
                .border(1.dp, if (primary) PynapliColors.Accent else PynapliColors.Border, PynapliShape.PillShape)
                .handCursor()
                .clickable(onClick = onClick)
                .then(
                    when (size) {
                        PynapliButtonSize.Large -> Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                        PynapliButtonSize.Compact -> Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (primary) PynapliColors.TextInverse else PynapliColors.TextPrimary,
            style = if (size == PynapliButtonSize.Large) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
