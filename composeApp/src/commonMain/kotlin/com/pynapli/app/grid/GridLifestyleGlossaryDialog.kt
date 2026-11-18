package com.pynapli.app.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.handCursor

@Composable
internal fun LifestyleGlossaryDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.handCursor()) { Text(text = "Close") }
        },
        title = { Text(text = "Lifestyle terms") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LIFESTYLE_GLOSSARY.forEach { (label, definition) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text = label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = definition,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

// Definitions sourced from LANGUAGE_AND_TAXONOMY_SPEC L274 onwards.
private val LIFESTYLE_GLOSSARY: List<Pair<String, String>> =
    listOf(
        "Soft swap" to "Open to some shared intimacy, with limits agreed beforehand.",
        "Full swap" to "Open to partner exchange when everyone involved consents.",
        "Same-room" to "Prefer experiences where partners stay in the same room.",
        "Separate-room" to "Open to partners being in separate spaces.",
        "New to lifestyle" to "Still learning or early in the lifestyle community.",
        "Some experience" to "Has spent some time in lifestyle spaces and is still finding their pace.",
        "Experienced" to "Comfortable with lifestyle spaces, norms, and communication.",
        "Kink-friendly" to "Open to respectful conversations about kink, boundaries, and consent.",
        "Vanilla/social" to "Interested in conventional social connection or lower-intensity lifestyle interaction.",
        "ENM" to "Ethical non-monogamy; open to multiple connections with everyone's knowledge and consent.",
        "Swinging" to "A lifestyle where people, often couples, consensually socialize or connect with others outside their primary relationship.",
    )
