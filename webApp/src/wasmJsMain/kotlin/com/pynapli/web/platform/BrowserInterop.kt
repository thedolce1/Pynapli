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
