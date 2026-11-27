package com.pynapli.app.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler

/** The dev desktop shell opens the page in the system browser. */
@Composable
internal actual fun rememberPolicyLinkOpener(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    return remember(uriHandler) { { url -> runCatching { uriHandler.openUri(url) } } }
}
