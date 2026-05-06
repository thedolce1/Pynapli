package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class GridQueryRequestDto(
    val radiusMeters: Int = 50_000,
    val limit: Int = 60,
    val cursor: String? = null,
    val minimumAge: Int? = null,
    val maximumAge: Int? = null,
    val relationshipContexts: List<String> = emptyList(),
    val linkedOnly: Boolean? = null,
    val onlineOnly: Boolean = false
)

@Serializable
data class GridQueryResponseDto(
    val items: List<GridCardDto>,
    val nextCursor: String? = null,
    val adSupported: Boolean = false,
    val advancedFiltersApplied: Boolean = false
)

@Serializable
data class GridCardDto(
    val userId: String,
    val displayName: String,
    val thumbnailUrl: String? = null,
    val online: Boolean,
    val distanceLabel: String?,
    val distanceMeters: Int? = null,
    val linkedPartner: LinkedPartnerMiniDto? = null,
    val rankToken: String
)

@Serializable
data class LinkedPartnerMiniDto(
    val userId: String,
    val displayName: String? = null,
    val thumbnailUrl: String? = null
)
