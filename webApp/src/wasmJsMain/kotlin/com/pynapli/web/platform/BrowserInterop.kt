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

internal fun geolocationAvailable(): Boolean = js("'geolocation' in navigator")

internal fun onDocumentVisible(handler: () -> Unit): Unit =
    js(
        """{
        document.addEventListener('visibilitychange', function () {
            if (document.visibilityState === 'visible') { handler(); }
        });
    }""",
    )

/**
 * Opens the browser image picker and reads the chosen file. Bytes cross the JS
 * boundary as one base64 string (single interop call; a per-byte copy would take
 * millions). Cancelling the OS dialog fires no callback — nothing is pending UI-side.
 */
internal fun openBrowserImagePicker(onPicked: (String, String) -> Unit): Unit =
    js(
        """{
        var input = document.createElement('input');
        input.type = 'file';
        input.accept = 'image/*';
        input.onchange = function () {
            var file = input.files && input.files[0];
            if (!file) { return; }
            var reader = new FileReader();
            reader.onload = function () {
                var bytes = new Uint8Array(reader.result);
                var binary = '';
                var chunk = 0x8000;
                for (var i = 0; i < bytes.length; i += chunk) {
                    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
                }
                onPicked(btoa(binary), file.type || 'image/jpeg');
            };
            reader.readAsArrayBuffer(file);
        };
        input.click();
    }""",
    )

internal fun requestBrowserGeolocation(
    onSuccess: (Double, Double, Double) -> Unit,
    onPermissionDenied: () -> Unit,
    onUnavailable: () -> Unit,
): Unit =
    js(
        """{
        navigator.geolocation.getCurrentPosition(
            function (position) {
                onSuccess(position.coords.latitude, position.coords.longitude, position.coords.accuracy);
            },
            function (error) {
                if (error.code === 1) { onPermissionDenied(); } else { onUnavailable(); }
            },
            { enableHighAccuracy: false, timeout: 15000, maximumAge: 60000 }
        );
    }""",
    )

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
