package com.pynapli.app.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliShape
import com.pynapli.shared.network.dto.MESSAGE_BODY_MAX_LENGTH

@Composable
internal fun TypingIndicator(participants: List<TypingParticipantUi>) {
    if (participants.isEmpty()) return
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(ConversationTestTags.typingIndicator),
        shape = PynapliShape.CardShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) {
                    Box(
                        modifier =
                            Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Text(
                text = participants.typingLabel(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun Composer(
    draft: String,
    canSend: Boolean,
    isSending: Boolean,
    isSendingPhoto: Boolean,
    isOverLimit: Boolean,
    remainingCharacters: Int,
    errorMessage: String?,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onPickPhoto: () -> Unit,
    // Hardware-keyboard close (web/desktop, WEB_PWA_GAMEPLAN.md W3.3): Esc in the
    // composer closes the open conversation. Null where the container owns dismissal.
    onEscape: (() -> Unit)? = null,
) {
    val showCharacterCount = draft.length >= MESSAGE_BODY_MAX_LENGTH - 200 || isOverLimit
    val helperText =
        when {
            errorMessage != null -> errorMessage
            isOverLimit -> "${-remainingCharacters} characters over limit"
            showCharacterCount -> "$remainingCharacters characters left"
            else -> null
        }
    val helperColor =
        if (errorMessage != null || isOverLimit) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val sendButtonActive = canSend || isSending
    val sendButtonColor =
        if (sendButtonActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val sendContentColor =
        if (sendButtonActive) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = onPickPhoto,
                enabled = !isSending && !isSendingPhoto,
                modifier = Modifier.size(44.dp),
            ) {
                if (isSendingPhoto) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = "Send a photo",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChanged,
                enabled = !isSending,
                isError = errorMessage != null || isOverLimit,
                placeholder = { Text("Message") },
                modifier =
                    Modifier
                        .weight(1f)
                        // Hardware keyboards (web/desktop): Enter sends, Shift+Enter breaks the
                        // line, Esc closes. Soft keyboards commit through the IME and never
                        // reach this, so phone behavior is unchanged.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                (event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isShiftPressed -> {
                                    if (canSend) onSend()
                                    // Consumed either way so a bare Enter never inserts a
                                    // stray newline into an empty/over-limit draft.
                                    true
                                }

                                event.key == Key.Escape && onEscape != null -> {
                                    onEscape()
                                    true
                                }

                                else -> false
                            }
                        },
                maxLines = 4,
            )
            IconButton(
                onClick = onSend,
                enabled = canSend,
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(sendButtonColor),
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        color = sendContentColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message",
                        tint = sendContentColor,
                    )
                }
            }
        }
        helperText?.let { text ->
            Text(
                text = text,
                color = helperColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
