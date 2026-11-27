package com.pynapli.app.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliBottomSheet
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.MoreVert
import com.pynapli.app.design.icons.filled.People

/**
 * The conversation's overflow button. Its only items today are the server's "We've met in person"
 * offers, fetched when it opens ([onMenuOpened]); while they load, or when there are none, it says so.
 */
@Composable
internal fun ConversationOverflowButton(
    menu: MetInPersonMenuUi,
    onMenuOpened: () -> Unit,
    onMetInPerson: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val items = menu.items
    Box {
        IconButton(
            onClick = {
                open = true
                onMenuOpened()
            },
            modifier = Modifier.handCursor().testTag(MetInPersonTestTags.overflow),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Conversation options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (items.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(if (menu.isLoading) "Loading…" else "Nothing here right now") },
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.handCursor(enabled = false),
                )
            }
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        open = false
                        onMetInPerson(item.subjectUserId)
                    },
                    modifier = Modifier.handCursor().testTag(MetInPersonTestTags.menuItem(item.subjectUserId)),
                )
            }
        }
    }
}

@Composable
internal fun MetInPersonSheet(
    ui: MetInPersonSheetUi,
    onIncludePartnerChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onTakeBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    PynapliBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .testTag(MetInPersonTestTags.sheet),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Filled.People,
                    contentDescription = null,
                    tint = PynapliColors.MetInPerson,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = ui.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = ui.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.partnerTickLabel?.let { label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = ui.includePartner,
                        onCheckedChange = onIncludePartnerChange,
                        enabled = !ui.isSubmitting,
                        modifier = Modifier.handCursor(enabled = !ui.isSubmitting).testTag(MetInPersonTestTags.partnerTick),
                    )
                    Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            ui.errorMessage?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                    Text("Cancel")
                }
                if (ui.showTakeBack) {
                    TextButton(
                        onClick = onTakeBack,
                        enabled = !ui.isSubmitting,
                        modifier = Modifier.handCursor(enabled = !ui.isSubmitting).testTag(MetInPersonTestTags.takeBack),
                    ) {
                        Text("Take it back")
                    }
                }
                if (ui.showConfirm) {
                    Button(
                        onClick = onConfirm,
                        enabled = !ui.isSubmitting,
                        modifier = Modifier.handCursor(enabled = !ui.isSubmitting).testTag(MetInPersonTestTags.confirm),
                    ) {
                        Text("Confirm")
                    }
                }
            }
        }
    }
}

object MetInPersonTestTags {
    const val overflow: String = "conversation-overflow"
    const val sheet: String = "met-in-person-sheet"
    const val partnerTick: String = "met-in-person-partner"
    const val confirm: String = "met-in-person-confirm"
    const val takeBack: String = "met-in-person-take-back"

    fun menuItem(subjectUserId: String): String = "met-in-person-item-$subjectUserId"
}
