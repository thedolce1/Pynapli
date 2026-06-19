package com.pynapli.app.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * Canonical in-sheet section labels. Previously `SectionTitle` was defined twice (grid + swipe
 * filter chrome) and `SectionLabel` was a third variant in Start Tonight; these are the single
 * source so every sheet's section rhythm matches.
 */

/** Section title inside a sheet body (e.g. "Distance", "Age", "Intent"). */
@Composable
fun PynapliSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** Secondary intro / helper line under a section title or sheet header. */
@Composable
fun PynapliSectionIntro(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
