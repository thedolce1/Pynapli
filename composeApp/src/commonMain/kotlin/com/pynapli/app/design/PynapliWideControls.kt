package com.pynapli.app.design

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/*
 * Comfortable control sizes on tablets and computers.
 *
 * Measured 2026-11-10: every clickable already had a 48dp TOUCH area (pills reserve it, and Compose
 * widens small targets for touch), but the DRAWN controls were small: filter pills 26dp tall, chips
 * 32dp, the Activity dismiss "X" 28dp, message and delete buttons 32dp. On a phone held close that
 * density is the design; on an iPad at arm's length, or a computer with a mouse (which gets no touch
 * widening), older members aim at what they can see. Medium+ windows draw them at 40dp; phones are
 * unchanged.
 */

/** True on tablets and computers (Medium+ windows), where controls are drawn at comfortable sizes. */
@Composable
fun pynapliWideControls(): Boolean = currentPynapliWindowSizeClass() != PynapliWindowSizeClass.Compact

/** Chips and clickable pills: at least [PynapliLayout.WideChipHeight] tall on Medium+ windows. */
@Composable
fun Modifier.wideChipHeight(): Modifier = if (pynapliWideControls()) heightIn(min = PynapliLayout.WideChipHeight) else this

/** A small icon button's drawn size: [compact] on phones, at least [PynapliLayout.WideIconButtonSize] on Medium+. */
@Composable
fun pynapliIconButtonSize(compact: Dp): Dp = if (pynapliWideControls()) maxOf(compact, PynapliLayout.WideIconButtonSize) else compact
