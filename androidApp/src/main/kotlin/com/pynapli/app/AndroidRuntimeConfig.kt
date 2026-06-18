package com.pynapli.app

import com.pynapli.shared.core.config.PynapliConfig
import com.pynapli.shared.core.config.PynapliEnvironment

internal object AndroidRuntimeConfig {
    val pynapliConfig: PynapliConfig =
        PynapliConfig(
            environment = BuildConfig.PYNAPLI_ENVIRONMENT.toPynapliEnvironment(),
            apiBaseUrl = BuildConfig.PYNAPLI_API_BASE_URL.trim(),
            websocketBaseUrl = BuildConfig.PYNAPLI_WEBSOCKET_BASE_URL.trim(),
        )

    private fun String.toPynapliEnvironment(): PynapliEnvironment =
        PynapliEnvironment.entries.firstOrNull { candidate -> candidate.name.equals(this.trim(), ignoreCase = true) }
            ?: PynapliEnvironment.Local
}
