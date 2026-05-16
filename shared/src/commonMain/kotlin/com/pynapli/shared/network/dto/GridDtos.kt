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
    val onlineOnly: Boolean = false,
    val verifiedOnly: Boolean = false,
    val facePhotoVerifiedOnly: Boolean = false,
    val ageVerifiedOnly: Boolean = false,
    val discoveryStyles: List<String> = emptyList(),
    val lifestyleTags: List<String> = emptyList(),
    val logisticsTags: List<String> = emptyList(),
    val tonightAvailableOnly: Boolean = false,
    val intents: List<String> = emptyList()
)

@Serializable
data class GridQueryResponseDto(
    val items: List<GridCardDto>,
    val nextCursor: String? = null,
    val adSupported: Boolean = false,
    val advancedFiltersApplied: Boolean = false,
    val resultLimitReached: Boolean = false,
    val resultLimitTier: String? = null
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
    val thumbnailUrl: String? = null,
    val online: Boolean = false
)

@Serializable
data class SavedGridFilterDto(
    val id: String,
    val name: String,
    val payload: GridQueryRequestDto,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class SavedGridFiltersListDto(
    val items: List<SavedGridFilterDto>
)

@Serializable
data class CreateSavedGridFilterRequestDto(
    val name: String,
    val payload: GridQueryRequestDto
)

@Serializable
data class SetTonightAvailableRequestDto(
    val durationHours: Int? = null
)

@Serializable
data class TonightAvailabilityDto(
    val availableUntil: String?
)
