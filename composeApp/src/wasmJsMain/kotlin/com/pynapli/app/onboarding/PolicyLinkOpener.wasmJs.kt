package com.pynapli.app.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler

/** Compose's web URI handler opens a new browser tab (`window.open(uri, "_blank")`): the app tab stays put. */
@Composable
internal actual fun rememberPolicyLinkOpener(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    return remember(uriHandler) { { url -> runCatching { uriHandler.openUri(url) } } }
}
