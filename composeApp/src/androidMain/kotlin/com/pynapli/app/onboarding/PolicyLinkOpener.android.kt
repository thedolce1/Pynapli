package com.pynapli.app.onboarding

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler

// The Custom Tabs protocol's session extra (androidx.browser's CustomTabsIntent.EXTRA_SESSION). Its
// presence, even with a null binder, asks a supporting browser to open the page as a tab over the
// app; a browser without Custom Tabs ignores it and opens the page normally.
private const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"

@Composable
internal actual fun rememberPolicyLinkOpener(): (String) -> Unit {
    val context = LocalContext.current
    val fallback = LocalUriHandler.current
    return remember(context, fallback) {
        { url ->
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    putExtras(Bundle().apply { putBinder(EXTRA_CUSTOM_TABS_SESSION, null) })
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            val opened = runCatching { context.startActivity(intent) }
            if (opened.exceptionOrNull() is ActivityNotFoundException) {
                // No browser that takes the intent: try the platform handler; nothing more to do if
                // that fails too.
                runCatching { fallback.openUri(url) }
            }
        }
    }
}
