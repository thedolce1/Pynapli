package com.pynapli.app.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

// The desktop test window is wider than Compact, so these exercise the keyboard (Medium+) path.
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class CloseOnEscapeTest {
    @Test
    fun escapeClosesTheSurfaceWithoutClickingFirst() =
        runComposeUiTest {
            var closes = 0
            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(900.dp, 700.dp).closeOnEscape { closes += 1 })
                }
            }

            onRoot().performKeyInput { pressKey(Key.Escape) }

            assertEquals(1, closes, "Esc closes the surface once")
        }

    @Test
    fun escapeInsideATextFieldKeepsTheSurfaceOpen() =
        runComposeUiTest {
            var closes = 0
            setContent {
                PynapliTheme(themeMode = "dark") {
                    var text by remember { mutableStateOf("half-written bio") }
                    Column(modifier = Modifier.size(900.dp, 700.dp).closeOnEscape { closes += 1 }) {
                        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.testTag("field"))
                    }
                }
            }

            onNodeWithTag("field").performClick()
            onNodeWithTag("field").performKeyInput { pressKey(Key.Escape) }

            assertEquals(0, closes, "Esc while typing must not close the surface (and drop the edit)")
        }

    @Test
    fun otherKeysAreIgnored() =
        runComposeUiTest {
            var closes = 0
            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(900.dp, 700.dp).closeOnEscape { closes += 1 })
                }
            }

            onRoot().performKeyInput { pressKey(Key.Enter) }
            onRoot().performKeyInput { pressKey(Key.Spacebar) }

            assertEquals(0, closes)
        }

    @Test
    fun escapeClosesASurfaceThatAlsoRegistersABackHandler() =
        runComposeUiTest {
            var closes = 0
            var backs = 0
            setContent {
                PynapliTheme(themeMode = "dark") {
                    @Suppress("DEPRECATION")
                    BackHandler { backs += 1 }
                    Box(modifier = Modifier.size(900.dp, 700.dp).closeOnEscape { closes += 1 })
                }
            }

            onRoot().performKeyInput { pressKey(Key.Escape) }

            assertEquals(1, closes + backs, "Esc closes exactly once (closes=$closes, backs=$backs)")
        }
}
