package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class PresenceHeartbeatResponseDto(
    val online: Boolean,
    val expiresInSeconds: Int,
)

@Serializable
data class PresenceDeltaDto(
    val userId: String,
    val online: Boolean,
    val lastActiveAt: String,
)

@Serializable
data class PresenceStreamSubscribeDto(
    val userIds: List<String>,
)
