package com.pynapli.shared.network

interface AccessTokenProvider {
    suspend fun accessToken(): String?
}

class StaticAccessTokenProvider(
    private val token: String?,
) : AccessTokenProvider {
    override suspend fun accessToken(): String? = token
}

const val APP_CHECK_HEADER = "X-Firebase-AppCheck"

fun interface AppCheckTokenProvider {
    suspend fun appCheckToken(): String?
}

object NoAppCheckTokenProvider : AppCheckTokenProvider {
    override suspend fun appCheckToken(): String? = null
}

class StaticAppCheckTokenProvider(
    private val token: String?,
) : AppCheckTokenProvider {
    override suspend fun appCheckToken(): String? = token
}
