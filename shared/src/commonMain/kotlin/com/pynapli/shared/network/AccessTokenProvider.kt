package com.pynapli.shared.network

interface AccessTokenProvider {
    suspend fun accessToken(): String?
}

class StaticAccessTokenProvider(
    private val token: String?
) : AccessTokenProvider {
    override suspend fun accessToken(): String? = token
}
