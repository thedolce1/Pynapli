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

private fun sessionStorageGetItem(key: String): String? = js("window.sessionStorage.getItem(key)")

private fun sessionStorageSetItem(
    key: String,
    value: String,
): Unit = js("window.sessionStorage.setItem(key, value)")

private fun sessionStorageRemoveItem(key: String): Unit = js("window.sessionStorage.removeItem(key)")

internal fun windowLocationHostname(): String = js("window.location.hostname")

// Optional prod API origin injected by index.html at deploy time (no Wasm rebuild). Empty/unset -> null.
internal fun apiBaseOverride(): String? = js("window.__PYNAPLI_API_BASE__ || null")

internal fun windowLocationPathname(): String = js("window.location.pathname")

internal fun historyPushState(path: String): Unit = js("window.history.pushState(null, '', path)")

internal fun historyReplaceState(path: String): Unit = js("window.history.replaceState(null, '', path)")

internal fun historyGo(delta: Int): Unit = js("window.history.go(delta)")

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

/**
 * Calls [handler] when ANOTHER tab of this origin changes localStorage [key] (the `storage` event
 * never fires in the tab that wrote). A null event key means that tab cleared all of storage.
 */
internal fun onOtherTabStorageChange(
    key: String,
    handler: () -> Unit,
): Unit =
    js(
        """{
        window.addEventListener('storage', function (event) {
            if (event.key === key || event.key === null) { handler(); }
        });
    }""",
    )

/**
 * Calls [onGranted] only when the site already holds the geolocation permission, so a caller can
 * refresh a fix without ever showing the browser's prompt (the resume path). Browsers without the
 * Permissions API, or that reject the query, simply never call back.
 */
internal fun whenGeolocationAlreadyGranted(onGranted: () -> Unit): Unit =
    js(
        """{
        if (!navigator.permissions || !navigator.permissions.query) { return; }
        navigator.permissions.query({ name: 'geolocation' }).then(function (status) {
            if (status.state === 'granted') { onGranted(); }
        }).catch(function () {});
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

/**
 * Browser `sessionStorage`: like [BrowserLocalStorage] but scoped to the tab and gone when it
 * closes. For what must not outlive the visit (a sign-up waiting for its emailed code).
 */
internal object BrowserSessionStorage {
    fun get(key: String): String? = runCatching { sessionStorageGetItem(key) }.getOrNull()

    fun set(
        key: String,
        value: String,
    ) {
        runCatching { sessionStorageSetItem(key, value) }
    }

    fun remove(key: String) {
        runCatching { sessionStorageRemoveItem(key) }
    }
}

/**
 * The browser's default font size in CSS px: 16 unless the user changed it in the browser's
 * settings (Chrome: Appearance > Font size; Large is 20, Very large is 24). Read through a probe
 * element sized `medium`, the CSS keyword for exactly that preference.
 */
internal fun browserDefaultFontSizePx(): Double =
    js(
        "(() => { const p = document.createElement('div'); p.style.cssText = 'position:absolute;visibility:hidden;font-size:medium'; document.body.appendChild(p); const px = parseFloat(getComputedStyle(p).fontSize) || 16; p.remove(); return px; })()",
    )
