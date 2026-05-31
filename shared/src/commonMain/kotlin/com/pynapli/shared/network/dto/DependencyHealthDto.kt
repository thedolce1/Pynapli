package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class DeepHealthDto(
    val status: String,
    val service: String,
    val environment: String,
    val dependencies: List<DependencyHealthDto>,
)

@Serializable
data class DependencyHealthDto(
    val name: String,
    val status: String,
    val detail: String? = null,
)
