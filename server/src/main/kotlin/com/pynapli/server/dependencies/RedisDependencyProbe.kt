package com.pynapli.server.dependencies

import com.pynapli.shared.network.dto.DependencyHealthDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RedisDependencyProbe(
    private val redisCache: RedisCache,
) : DependencyProbe {
    override val name: String = "redis"

    override suspend fun check(): DependencyHealthDto =
        withContext(Dispatchers.IO) {
            runCatching {
                redisCache.commands.ping()
            }.fold(
                onSuccess = { pong ->
                    DependencyHealthDto(
                        name = name,
                        status = if (pong == "PONG") "ok" else "failed",
                        detail = pong.takeUnless { it == "PONG" },
                    )
                },
                onFailure = { error ->
                    DependencyHealthDto(
                        name = name,
                        status = "failed",
                        detail = error.message ?: error::class.simpleName,
                    )
                },
            )
        }

    override fun close() {
        // Redis lifecycle is owned by ServerDependencies.
    }
}
