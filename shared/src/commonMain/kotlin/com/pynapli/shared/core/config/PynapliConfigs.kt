package com.pynapli.shared.core.config

object PynapliConfigs {
    val LocalAndroidEmulator = PynapliConfig(
        environment = PynapliEnvironment.Local,
        apiBaseUrl = "http://10.0.2.2:8080",
        websocketBaseUrl = "ws://10.0.2.2:8080"
    )

    val LocalHost = PynapliConfig(
        environment = PynapliEnvironment.Local,
        apiBaseUrl = "http://localhost:8080",
        websocketBaseUrl = "ws://localhost:8080"
    )
}
