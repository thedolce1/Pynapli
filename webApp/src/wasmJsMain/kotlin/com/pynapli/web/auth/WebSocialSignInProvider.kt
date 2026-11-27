@file:OptIn(ExperimentalWasmJsInterop::class)

package com.pynapli.web.auth

import com.pynapli.app.auth.SocialCredentialResult
import com.pynapli.app.auth.SocialSignInProvider
import com.pynapli.shared.auth.SocialProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.js.ExperimentalWasmJsInterop

/**
 * "Continue with Google / Apple" on the web: Google Identity Services and Sign in with Apple JS,
 * driven through `/pynapli-social.js` (see the header there for why the provider's own button sits
 * in a small layer above the canvas).
 *
 * Client ids come from the page, not the bundle, so a deploy sets them without a Wasm rebuild
 * (index.html: `window.__PYNAPLI_GOOGLE_CLIENT_ID__`, `__PYNAPLI_APPLE_SERVICES_ID__`,
 * `__PYNAPLI_APPLE_REDIRECT_URI__`). They are public identifiers, not secrets; a blank one hides
 * that provider. No script is requested until [warmUp] hears the server switched a provider on.
 */
class WebSocialSignInProvider : SocialSignInProvider {
    private val googleClientId: String = pageConfig("__PYNAPLI_GOOGLE_CLIENT_ID__").trim()
    private val appleServicesId: String = pageConfig("__PYNAPLI_APPLE_SERVICES_ID__").trim()

    // Apple validates it against the Services ID's registered return URLs even in popup mode.
    private val appleRedirectUri: String =
        pageConfig("__PYNAPLI_APPLE_REDIRECT_URI__").trim().ifEmpty { pageOrigin() + "/" }

    // The helper script's load: in flight, or finished (true when it loaded). A failed load (offline)
    // is retried on the next attempt.
    private var helper: CompletableDeferred<Boolean>? = null
    private var helperLoaded = false
    private var helperFailed = false

    override fun supports(provider: SocialProvider): Boolean =
        when (provider) {
            SocialProvider.Google -> googleClientId.isNotEmpty()
            SocialProvider.Apple -> appleServicesId.isNotEmpty()
        }

    override fun warmUp(providers: Set<SocialProvider>) {
        val google = SocialProvider.Google in providers && supports(SocialProvider.Google)
        val apple = SocialProvider.Apple in providers && supports(SocialProvider.Apple)
        if (!google && !apple) return
        ensureHelper().invokeOnCompletion { if (helperLoaded) socialWarmUp(google, apple) }
    }

    override suspend fun requestIdToken(
        provider: SocialProvider,
        nonce: String,
    ): SocialCredentialResult {
        if (!supports(provider)) return SocialCredentialResult.Unavailable
        if (!ensureHelper().await()) return SocialCredentialResult.Unavailable
        return suspendCancellableCoroutine { continuation ->
            val done: (String, String, String, String, String) -> Unit = { kind, idToken, code, givenName, message ->
                if (continuation.isActive) {
                    continuation.resume(toResult(kind, idToken, code, givenName, message))
                }
            }
            when (provider) {
                SocialProvider.Google -> socialRequestGoogle(googleClientId, nonce, done)
                // The helper makes a fresh state per attempt (crypto.getRandomValues) and checks Apple's echo.
                SocialProvider.Apple -> socialRequestApple(appleServicesId, appleRedirectUri, nonce, done)
            }
            continuation.invokeOnCancellation { socialCancel() }
        }
    }

    override suspend fun onSignedOut() {
        if (helperLoaded) socialSignedOut()
    }

    private fun ensureHelper(): CompletableDeferred<Boolean> {
        helper?.let { existing -> if (!helperFailed) return existing }
        val deferred = CompletableDeferred<Boolean>()
        helper = deferred
        helperFailed = false
        loadSocialHelper(
            onReady = {
                helperLoaded = true
                deferred.complete(true)
            },
            onFailed = {
                helperFailed = true
                deferred.complete(false)
            },
        )
        return deferred
    }

    private fun toResult(
        kind: String,
        idToken: String,
        code: String,
        givenName: String,
        message: String,
    ): SocialCredentialResult =
        when (kind) {
            "success" ->
                if (idToken.isEmpty()) {
                    SocialCredentialResult.Error(message.ifEmpty { GENERIC_ERROR })
                } else {
                    SocialCredentialResult.Success(
                        idToken = idToken,
                        authorizationCode = code.ifEmpty { null },
                        givenName = givenName.ifEmpty { null },
                    )
                }

            "cancelled" -> SocialCredentialResult.Cancelled

            "unavailable" -> SocialCredentialResult.Unavailable

            else -> SocialCredentialResult.Error(message.ifEmpty { GENERIC_ERROR })
        }

    private companion object {
        const val GENERIC_ERROR = "Sign-in didn't work. Try again."
    }
}

// Kotlin/Wasm JS interop requires js() to be the whole body of a top-level function. Values cross
// as non-null strings ("" for absent), like the rest of BrowserInterop.

private fun pageConfig(name: String): String = js("(typeof window[name] === 'string') ? window[name] : ''")

private fun pageOrigin(): String = js("window.location.origin")

/** Injects `/pynapli-social.js` once (first-party, same origin). Absolute path: deep links nest. */
private fun loadSocialHelper(
    onReady: () -> Unit,
    onFailed: () -> Unit,
): Unit =
    js(
        """{
        if (window.__pynapliSocial) { onReady(); return; }
        var script = document.createElement('script');
        script.src = '/pynapli-social.js';
        script.async = true;
        script.onload = function () { if (window.__pynapliSocial) { onReady(); } else { onFailed(); } };
        script.onerror = function () { script.remove(); onFailed(); };
        document.head.appendChild(script);
    }""",
    )

private fun socialWarmUp(
    google: Boolean,
    apple: Boolean,
): Unit = js("window.__pynapliSocial.warmUp(google, apple)")

private fun socialRequestGoogle(
    clientId: String,
    nonce: String,
    done: (String, String, String, String, String) -> Unit,
): Unit = js("window.__pynapliSocial.requestGoogle(clientId, nonce, done)")

private fun socialRequestApple(
    servicesId: String,
    redirectUri: String,
    nonce: String,
    done: (String, String, String, String, String) -> Unit,
): Unit = js("window.__pynapliSocial.requestApple(servicesId, redirectUri, nonce, done)")

private fun socialCancel(): Unit = js("{ if (window.__pynapliSocial) { window.__pynapliSocial.cancel(); } }")

private fun socialSignedOut(): Unit = js("{ if (window.__pynapliSocial) { window.__pynapliSocial.signedOut(); } }")
