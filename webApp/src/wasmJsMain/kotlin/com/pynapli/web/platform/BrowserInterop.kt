@file:OptIn(ExperimentalWasmJsInterop::class)

package com.pynapli.web.platform

import kotlin.js.ExperimentalWasmJsInterop

// Kotlin/Wasm JS interop requires js() to be the whole body of a top-level function.

private fun localStorageGetItem(key: String): String? = js("window.localStorage.getItem(key)")

private fun localStorageSetItem(
    key: String,
    value: String,
): Unit = js("window.localStorage.setItem(key, value)")

private fun localStorageRemoveItem(key: String): Unit = js("window.localStorage.removeItem(key)")

internal fun windowLocationHostname(): String = js("window.location.hostname")

internal fun windowLocationPathname(): String = js("window.location.pathname")

internal fun historyPushState(path: String): Unit = js("window.history.pushState(null, '', path)")

internal fun historyReplaceState(path: String): Unit = js("window.history.replaceState(null, '', path)")

internal fun onWindowPopState(handler: () -> Unit): Unit = js("window.addEventListener('popstate', handler)")

internal fun onWindowEvent(
    name: String,
    handler: () -> Unit,
): Unit = js("window.addEventListener(name, handler)")

internal fun installPromptAvailable(): Boolean = js("window.__pynapliInstallPrompt != null")

internal fun showInstallPrompt(): Unit =
    js(
        """{
        if (window.__pynapliInstallPrompt) {
            window.__pynapliInstallPrompt.prompt();
            window.__pynapliInstallPrompt = null;
        }
    }""",
    )

/**
 * Browser `localStorage` behind a tiny seam so stores stay testable and every browser
 * touchpoint is greppable in one place. Values are strings only; callers own encoding.
 */
internal object BrowserLocalStorage {
    fun get(key: String): String? = runCatching { localStorageGetItem(key) }.getOrNull()

    fun set(
        key: String,
        value: String,
    ) {
        // Quota errors (private-browsing modes) degrade to session-only behavior.
        runCatching { localStorageSetItem(key, value) }
    }

    fun remove(key: String) {
        runCatching { localStorageRemoveItem(key) }
    }
}
