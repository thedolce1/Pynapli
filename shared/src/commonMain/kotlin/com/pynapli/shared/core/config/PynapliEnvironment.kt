package com.pynapli.shared.core.config

enum class PynapliEnvironment {
    Local,
    Dev,
    Staging,
    Prod
}

data class PynapliConfig(
    val environment: PynapliEnvironment,
    val apiBaseUrl: String,
    val websocketBaseUrl: String
)

