package com.pynapli.shared.network

import kotlinx.serialization.json.Json

val PynapliJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
