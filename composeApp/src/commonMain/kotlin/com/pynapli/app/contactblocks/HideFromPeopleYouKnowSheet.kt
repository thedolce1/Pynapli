package com.pynapli.app.contactblocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliBottomSheet
import com.pynapli.app.design.PynapliSheetColumn
import com.pynapli.app.design.PynapliSheetTitle
import com.pynapli.app.design.handCursor
import kotlinx.coroutines.launch

/** The explanation shown on the sheet (PROFILE_SETTINGS_SPEC, "Hide from people you know"). */
internal val HideFromPeopleYouKnowCopy =
    listOf(
        "Add email addresses of people who should never come across you here. If one of them has an account, or joins later, you won't show up for them in Grid, Swipe, Playing Tonight, Psync or Activity, and your likes, views and messages won't reach them. We will never tell you whether an address belongs to a member, and nothing you see changes.",
        "This does not hide you from someone you've already matched, chatted with, liked or been viewed by (block them from their profile), or from someone using a different email or a second account.",
        "It does not hide your partner (they can add the address too). It does not hide them from you, and it doesn't affect reports or blocks.",
        "We store a scrambled code, not the address, so we can't show you the list. To remove one, type it again, or remove them all.",
    )

private enum class EntryMode { Add, Remove }

/**
 * "Hide from people you know": type an address (the primary path), Add; the count of addresses
 * added; "Remove an address" (type it again) and "Remove all". Contacts picker: a follow-up (it needs
 * platform code in each shell).
 */
@Composable
fun HideFromPeopleYouKnowSheet(
    stateHolder: ContactBlocksStateHolder,
    onDismiss: () -> Unit,
) {
    val state by stateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(EntryMode.Add) }
    var confirmRemoveAll by remember { mutableStateOf(false) }

    LaunchedEffect(stateHolder) {
        stateHolder.clearMessages()
        stateHolder.load()
    }

    val canSubmit = !state.isSaving && address.isNotBlank()
    val submit: () -> Unit = {
        if (canSubmit) {
            scope.launch {
                val done = if (mode == EntryMode.Add) stateHolder.add(address) else stateHolder.remove(address)
                if (done) address = ""
            }
        }
    }

    PynapliBottomSheet(onDismiss = onDismiss, modifier = Modifier.testTag("hide-from-people-you-know-sheet")) {
        PynapliSheetColumn {
            PynapliSheetTitle("Hide from people you know")
            HideFromPeopleYouKnowCopy.forEach { paragraph ->
                Text(
                    text = paragraph,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(if (mode == EntryMode.Add) "Email address" else "Email address to remove") },
                singleLine = true,
                enabled = !state.isSaving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag("hide-from-people-you-know-field"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = state.countLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = submit,
                    enabled = canSubmit,
                    modifier = Modifier.handCursor(enabled = canSubmit).testTag("hide-from-people-you-know-submit"),
                ) {
                    Text(if (mode == EntryMode.Add) "Add" else "Remove")
                }
            }
            (state.errorMessage ?: state.confirmation)?.let { message ->
                Text(
                    text = message,
                    color = if (state.errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = {
                        mode = if (mode == EntryMode.Add) EntryMode.Remove else EntryMode.Add
                        address = ""
                        stateHolder.clearMessages()
                    },
                    enabled = !state.isSaving,
                    modifier = Modifier.handCursor(enabled = !state.isSaving),
                ) {
                    Text(if (mode == EntryMode.Add) "Remove an address" else "Add an address")
                }
                val canRemoveAll = !state.isSaving && (state.count ?: 0) > 0
                TextButton(
                    onClick = { confirmRemoveAll = true },
                    enabled = canRemoveAll,
                    modifier = Modifier.handCursor(enabled = canRemoveAll),
                ) {
                    Text("Remove all")
                }
            }
            if (confirmRemoveAll) {
                Text(
                    text = "Remove every address you've added? You'll show up again for anyone they belonged to, and what you sent them meanwhile can reach them.",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { confirmRemoveAll = false },
                        modifier = Modifier.handCursor(),
                    ) {
                        Text("Keep them")
                    }
                    Button(
                        onClick = {
                            confirmRemoveAll = false
                            scope.launch { stateHolder.removeAll() }
                        },
                        enabled = !state.isSaving,
                        modifier = Modifier.handCursor(enabled = !state.isSaving),
                    ) {
                        Text("Remove all")
                    }
                }
            }
        }
    }
}
