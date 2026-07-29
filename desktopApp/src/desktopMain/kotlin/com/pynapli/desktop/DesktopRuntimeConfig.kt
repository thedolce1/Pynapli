package com.pynapli.desktop

import com.pynapli.shared.core.config.PynapliConfig
import com.pynapli.shared.core.config.PynapliEnvironment
import java.net.URI

/**
 * Where the desktop shell points. Defaults to the local Ktor server, overridable with
 * `-Dpynapli.apiBaseUrl=...` for pointing at a staging deploy.
 *
 * REFUSES anything non-local by default. This shell keeps session tokens in a plaintext file
 * (`DesktopSessionStore`) and has no App Check attestation, so pointing it at staging or prod would
 * put real credentials on disk unprotected. `-Dpynapli.allowRemote=true` is the deliberate opt-out
 * for someone who understands that; it is not the default, because the failure mode is silent.
 */
internal object DesktopRuntimeConfig {
    val pynapliConfig: PynapliConfig =
        resolveDesktopConfig(
            apiBaseUrl = System.getProperty("pynapli.apiBaseUrl"),
            allowRemote = System.getProperty("pynapli.allowRemote")?.toBoolean() == true,
        )

    /** Same always-on diagnostic strip the web shell shows outside prod. */
    val testerDiagnosticLabel: String
        get() = "Desktop | Env: ${pynapliConfig.environment.name} | API: ${pynapliConfig.apiBaseUrl}"
}

internal const val DESKTOP_DEFAULT_API_BASE = "http://localhost:8080"

/**
 * Pure resolution + guard, split out from [DesktopRuntimeConfig] so the refusal is covered by tests
 * rather than trusted by inspection — it is the only thing standing between a plaintext token store
 * and a real environment.
 *
 * @throws IllegalArgumentException when [apiBaseUrl] is remote and [allowRemote] is false.
 */
internal fun resolveDesktopConfig(
    apiBaseUrl: String?,
    allowRemote: Boolean,
): PynapliConfig {
    val resolved =
        apiBaseUrl
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
            ?: DESKTOP_DEFAULT_API_BASE

    // Compare the PARSED HOST, never a string prefix. `"http://localhost.attacker.example"
    // .startsWith("http://localhost")` is true, which would hand plaintext tokens and the dev-login
    // shortcut to an attacker-controlled host — the exact failure this guard exists to prevent.
    val host = runCatching { URI(resolved).host }.getOrNull()
    val isLocal = host != null && (host == "localhost" || host == "127.0.0.1" || host == "[::1]")

    require(isLocal || allowRemote) {
        "Desktop shell refuses to target '$resolved': it stores session tokens in plaintext and is " +
            "a development target only. Pass -Dpynapli.allowRemote=true if you genuinely intend this."
    }

    return PynapliConfig(
        environment = if (isLocal) PynapliEnvironment.Local else PynapliEnvironment.Staging,
        apiBaseUrl = resolved,
        websocketBaseUrl =
            resolved
                .replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://"),
    )
}
