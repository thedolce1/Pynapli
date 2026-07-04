package com.pynapli.app.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ConversationComposerKeyTest {
    @Test
    fun enterSendsShiftEnterDoesNotAndEscapeCloses() =
        runComposeUiTest {
            var sends = 0
            var escapes = 0

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 800.dp, height = 200.dp)) {
                        Composer(
                            draft = "hello",
                            canSend = true,
                            isSending = false,
                            isSendingPhoto = false,
                            isOverLimit = false,
                            remainingCharacters = 100,
                            errorMessage = null,
                            onDraftChanged = {},
                            onSend = { sends += 1 },
                            onPickPhoto = {},
                            onEscape = { escapes += 1 },
                        )
                    }
                }
            }

            val field = onNode(hasSetTextAction())
            field.performClick()

            field.performKeyInput { pressKey(Key.Enter) }
            assertEquals(1, sends, "Enter sends")

            field.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
            assertEquals(1, sends, "Shift+Enter must not send")

            field.performKeyInput { pressKey(Key.Escape) }
            assertEquals(1, escapes, "Escape closes the conversation")
        }

    @Test
    fun enterWithBlankDraftNeitherSendsNorThrows() =
        runComposeUiTest {
            var sends = 0

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 800.dp, height = 200.dp)) {
                        Composer(
                            draft = "",
                            canSend = false,
                            isSending = false,
                            isSendingPhoto = false,
                            isOverLimit = false,
                            remainingCharacters = 500,
                            errorMessage = null,
                            onDraftChanged = {},
                            onSend = { sends += 1 },
                            onPickPhoto = {},
                        )
                    }
                }
            }

            val field = onNode(hasSetTextAction())
            field.performClick()
            field.performKeyInput { pressKey(Key.Enter) }
            assertEquals(0, sends, "A blank draft never sends")
        }
}
