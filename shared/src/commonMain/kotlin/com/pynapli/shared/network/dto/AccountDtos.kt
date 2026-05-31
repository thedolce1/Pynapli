package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class AccountDataExportDto(
    val requestId: String,
    val status: String,
    val requestedAt: String,
    val completedAt: String? = null,
    val expiresAt: String? = null,
    val downloadUrl: String? = null,
    val message: String,
)

@Serializable
data class DeleteAccountRequestDto(
    val confirmation: String,
)

@Serializable
data class DeleteAccountResponseDto(
    val deleted: Boolean,
    val revokedSessionCount: Int,
    val message: String,
)
