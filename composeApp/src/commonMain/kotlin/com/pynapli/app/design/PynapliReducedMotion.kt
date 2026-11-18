package com.pynapli.app.design

import androidx.compose.runtime.Composable

/**
 * True when the system asks apps to cut motion (Android "Remove animations", iOS "Reduce Motion",
 * the browser's `prefers-reduced-motion`). Decorative entrances should then appear in place;
 * motion that follows the finger (a card being dragged) is unaffected.
 */
@Composable
internal expect fun rememberReducedMotion(): Boolean
