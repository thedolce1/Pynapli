package com.pynapli.shared.auth

data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val userStatus: String
)
