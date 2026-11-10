package com.pynapli.app.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/**
 * Shows the hand cursor while a mouse hovers this element, the cue desktop users read as "this is
 * clickable". Compose sets no cursor for clickables on its own, so without this every button, tile
 * and row on the web and desktop showed the plain arrow (measured 2026-11-10). Many members use the
 * web on a computer, and older users lean on that cue most.
 *
 * Apply it to anything a pointer can activate: `Modifier.handCursor().clickable { … }`, and on
 * Material buttons through their `modifier`. Pass the control's own [enabled] so a disabled control
 * keeps the arrow. `scripts/check-compose-tokens.ps1` fails the build on a clickable without it.
 * Touch-only platforms ignore it.
 */
fun Modifier.handCursor(enabled: Boolean = true): Modifier = if (enabled) pointerHoverIcon(PointerIcon.Hand) else this
