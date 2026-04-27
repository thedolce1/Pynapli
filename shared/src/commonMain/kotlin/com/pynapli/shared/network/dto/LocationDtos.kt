package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpdateCurrentLocationRequestDto(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Int? = null,
    val source: String,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null
)

@Serializable
data class CurrentLocationDto(
    val updatedAt: String,
    val accuracyMeters: Int?,
    val source: String,
    val geohash: String?,
    val city: String?,
    val region: String?,
    val country: String?
)

