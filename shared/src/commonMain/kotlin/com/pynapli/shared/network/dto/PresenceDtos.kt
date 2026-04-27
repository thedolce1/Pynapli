package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class PresenceHeartbeatResponseDto(
    val online: Boolean,
    val expiresInSeconds: Int
)
