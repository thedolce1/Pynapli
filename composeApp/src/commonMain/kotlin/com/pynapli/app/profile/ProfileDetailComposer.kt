package com.pynapli.app.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.automirrored.filled.Send
import com.pynapli.app.design.icons.filled.Verified

/** Server reason (PublicProfileDto.messagingUnavailableReason): photo-verify before messaging. */
internal const val VERIFY_TO_MESSAGE_REASON = "VERIFY_TO_MESSAGE"

/** What the profile's inline composer shows and does; built by the host from [ProfileComposerUiState]. */
data class ProfileComposerUi(
    val draft: String,
    val canSend: Boolean,
    val isSending: Boolean,
    val errorMessage: String?,
    val onDraftChange: (String) -> Unit,
    val onSend: () -> Unit,
)

internal object ProfileComposerTags {
    const val FIELD = "profile-composer-field"
    const val SEND = "profile-composer-send"
    const val VERIFY_TO_MESSAGE = "profile-verify-to-message"
}

/** "Say something…" pinned at the foot of a Grid profile: the first message, without leaving. */
@Composable
internal fun ProfileMessageComposer(composer: ProfileComposerUi) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = composer.draft,
                onValueChange = composer.onDraftChange,
                enabled = !composer.isSending,
                isError = composer.errorMessage != null,
                placeholder = { Text("Say something…") },
                shape = PynapliShape.CardShape,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (composer.canSend) composer.onSend() }),
                modifier =
                    Modifier
                        .weight(1f)
                        .testTag(ProfileComposerTags.FIELD)
                        // Hardware keyboards: Enter sends, Shift+Enter breaks the line (as in a chat).
                        .onPreviewKeyEvent { event ->
                            val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (event.type != KeyEventType.KeyDown || !enter || event.isShiftPressed) {
                                return@onPreviewKeyEvent false
                            }
                            if (composer.canSend) composer.onSend()
                            true
                        },
            )
            IconButton(
                onClick = composer.onSend,
                enabled = composer.canSend,
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (composer.canSend || composer.isSending) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ).handCursor(enabled = composer.canSend)
                        .testTag(ProfileComposerTags.SEND),
            ) {
                val contentColor =
                    if (composer.canSend || composer.isSending) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                if (composer.isSending) {
                    CircularProgressIndicator(
                        color = contentColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message",
                        tint = contentColor,
                    )
                }
            }
        }
        composer.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
        }
    }
}

/** Shown in place of messaging when photo verification is what stands in the way. */
@Composable
internal fun VerifyToMessageButton(onVerify: () -> Unit) {
    OutlinedButton(
        onClick = onVerify,
        modifier =
            Modifier
                .fillMaxWidth()
                .handCursor()
                .testTag(ProfileComposerTags.VERIFY_TO_MESSAGE),
    ) {
        Icon(
            imageVector = Icons.Filled.Verified,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Verify to message",
            modifier = Modifier.padding(start = 6.dp),
            maxLines = 1,
        )
    }
}
