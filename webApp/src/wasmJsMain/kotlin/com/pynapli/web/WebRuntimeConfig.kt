package com.pynapli.web

import com.pynapli.shared.core.config.PynapliConfig
import com.pynapli.shared.core.config.PynapliEnvironment
import com.pynapli.web.platform.apiBaseOverride
import com.pynapli.web.platform.windowLocationHostname

internal object WebRuntimeConfig {
    val pynapliConfig: PynapliConfig =
        run {
            val hostname = windowLocationHostname()
            val isLocal = hostname == "localhost" || hostname == "127.0.0.1"
            if (isLocal) {
                PynapliConfig(
                    environment = PynapliEnvironment.Local,
                    apiBaseUrl = "http://localhost:8080",
                    websocketBaseUrl = "ws://localhost:8080",
                )
            } else {
                // Production API origin is set at deploy time via window.__PYNAPLI_API_BASE__ in
                // index.html (no Wasm rebuild needed), falling back to the api.pynapli.com
                // convention. The WebSocket base is derived by swapping http(s) -> ws(s).
                val apiBaseUrl =
                    apiBaseOverride()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                        ?: "https://api.pynapli.com"
                val websocketBaseUrl =
                    apiBaseUrl
                        .replaceFirst("https://", "wss://")
                        .replaceFirst("http://", "ws://")
                PynapliConfig(
                    environment = PynapliEnvironment.Prod,
                    apiBaseUrl = apiBaseUrl,
                    websocketBaseUrl = websocketBaseUrl,
                )
            }
        }

    val testerDiagnosticLabel: String?
        get() =
            if (pynapliConfig.environment == PynapliEnvironment.Prod) {
                null
            } else {
                "Env: ${pynapliConfig.environment.name} | API: ${pynapliConfig.apiBaseUrl}"
            }
}
