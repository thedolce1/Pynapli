package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class EmailSignupRequestDto(
    val email: String,
    val password: String,
    val birthdate: String? = null,
    val confirmedAdult: Boolean = false,
    val acceptedTerms: Boolean = false,
    val acceptedPrivacy: Boolean = false,
    val deviceId: String? = null,
    val platform: String? = null,
    val appVersion: String? = null
)

@Serializable
data class EmailLoginRequestDto(
    val email: String,
    val password: String,
    val deviceId: String? = null,
    val platform: String? = null,
    val appVersion: String? = null
)

@Serializable
data class RefreshSessionRequestDto(
    val refreshToken: String
)

@Serializable
data class AuthSessionDto(
    val accessToken: String,
    val refreshToken: String,
    val user: AuthUserDto
)

@Serializable
data class AuthUserDto(
    val userId: String,
    val status: String
)

@Serializable
data class LogoutResponseDto(
    val loggedOut: Boolean
)
