package com.pynapli.app.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler

/** Safari through the platform handler for now; an SFSafariViewController sheet needs a Mac to verify. */
@Composable
internal actual fun rememberPolicyLinkOpener(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    return remember(uriHandler) { { url -> runCatching { uriHandler.openUri(url) } } }
}
