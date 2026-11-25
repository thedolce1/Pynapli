package com.pynapli.app.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliPill
import com.pynapli.app.design.PynapliSectionTitle
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Lock

@Composable
internal fun SectionTitle(text: String) {
    PynapliSectionTitle(text)
}

/**
 * A sheet filter chip. [locked] marks a paid option before it is tapped (plan G9): it still toggles,
 * and applying it takes out only that filter and opens the plans.
 */
@Composable
internal fun SheetToggleChip(
    label: String,
    active: Boolean,
    locked: Boolean = false,
    onClick: () -> Unit,
) {
    PynapliPill(
        text = label,
        selected = active,
        onClick = onClick,
        leading =
            if (locked) {
                {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Social or Select",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                }
            } else {
                null
            },
    )
}

/** Under a section of paid filters, for a viewer on Free. */
internal const val PAID_FILTERS_HINT: String = "Social or Select. Pick one to see the plans; your other filters still apply."

@Composable
internal fun ComingSoonSection(message: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
