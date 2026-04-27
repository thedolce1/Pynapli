package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorDto(
    val code: String,
    val message: String,
    val requestId: String,
    val details: Map<String, String> = emptyMap()
)

