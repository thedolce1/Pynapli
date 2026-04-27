package com.pynapli.shared.grid.domain

data class GridCard(
    val userId: String,
    val displayName: String,
    val thumbnailUrl: String?,
    val online: Boolean,
    val distanceMetersRounded: Int?,
    val linkedPartnerUserId: String?,
    val linkedPartnerThumbUrl: String?
)

