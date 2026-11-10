package com.pynapli.app.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Close

/**
 * Bounded post-signup nudge (ONBOARDING_AUTH_SPEC "Post-Signup Nudges"): honest,
 * dismissible, deep-links to the photo edit surface. Visibility bounding lives in
 * [OnboardingStateHolder]; the caller additionally gates on a photoless profile.
 */
@Composable
fun PhotoNudgeBanner(
    onAddPhoto: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // The banner sits at the very top of the screen: the surface color
            // extends behind the status bar while the controls stay below it.
            modifier =
                Modifier
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(
                text = "Profiles with photos get seen more.",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAddPhoto, modifier = Modifier.handCursor()) {
                Text("Add photo")
            }
            IconButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss photo reminder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
