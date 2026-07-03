package com.pynapli.web

import com.pynapli.shared.core.config.PynapliConfig
import com.pynapli.shared.core.config.PynapliEnvironment
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
                // Production API origin is a founder ops decision (W6.4); placeholder until
                // the server is deployed. The web app is only reachable locally before then.
                PynapliConfig(
                    environment = PynapliEnvironment.Prod,
                    apiBaseUrl = "https://api.pynapli.com",
                    websocketBaseUrl = "wss://api.pynapli.com",
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
