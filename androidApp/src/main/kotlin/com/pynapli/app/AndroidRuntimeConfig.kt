package com.pynapli.app

import com.pynapli.shared.core.config.PynapliConfig
import com.pynapli.shared.core.config.PynapliEnvironment

internal object AndroidRuntimeConfig {
    val pynapliConfig: PynapliConfig =
        PynapliConfig(
            environment = PynapliEnvironment.Local,
            apiBaseUrl = BuildConfig.PYNAPLI_API_BASE_URL.trim(),
            websocketBaseUrl = BuildConfig.PYNAPLI_WEBSOCKET_BASE_URL.trim(),
        )
}
