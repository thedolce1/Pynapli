package com.pynapli.shared.auth

import com.pynapli.shared.network.AccessTokenProvider
import com.pynapli.shared.network.dto.AuthSessionDto

interface SessionStore : AccessTokenProvider {
    suspend fun currentSession(): StoredSession?

    suspend fun save(session: AuthSessionDto)

    suspend fun clear()

    override suspend fun accessToken(): String? = currentSession()?.accessToken
}
