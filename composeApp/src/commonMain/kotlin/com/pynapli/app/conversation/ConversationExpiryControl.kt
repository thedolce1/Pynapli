package com.pynapli.app.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliShape
import com.pynapli.shared.network.dto.MessageExpiryOption

/** (value seconds or null = off) to a short label, in menu order. */
private val expiryChoices: List<Pair<Int?, String>> =
    listOf(
        null to "Off",
        MessageExpiryOption.OneHour to "1 hour",
        MessageExpiryOption.OneDay to "24 hours",
        MessageExpiryOption.OneWeek to "7 days",
        MessageExpiryOption.ThirtyDays to "30 days",
    )

internal fun expiryShortLabel(seconds: Int?): String = expiryChoices.firstOrNull { it.first == seconds }?.second ?: "Off"

/**
 * Compact "disappearing messages" control shown above the message list. Tapping opens a menu of the
 * spec timer options; the server gates the change on Social/Select and surfaces an upgrade error
 * otherwise (rendered through the conversation's existing error band).
 */
@Composable
internal fun ConversationExpiryControl(
    effectiveExpirySeconds: Int?,
    viewerExpirySeconds: Int?,
    isSettingExpiry: Boolean,
    onSetExpiry: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // The effective timer (what messages actually expire on) is what the user cares about; fall
    // back to the viewer's own when there is no shared effective value yet.
    val shown = effectiveExpirySeconds ?: viewerExpirySeconds

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(PynapliShape.PillShape)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), PynapliShape.PillShape)
                .clickable(enabled = !isSettingExpiry) { menuOpen = true }
                .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isSettingExpiry) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        } else {
            Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = if (shown == null) "Disappearing messages off" else "Disappearing messages: ${expiryShortLabel(shown)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            expiryChoices.forEach { (seconds, label) ->
                DropdownMenuItem(
                    text = { Text(if (seconds == viewerExpirySeconds) "$label ✓" else label) },
                    onClick = {
                        menuOpen = false
                        onSetExpiry(seconds)
                    },
                )
            }
        }
    }
}
