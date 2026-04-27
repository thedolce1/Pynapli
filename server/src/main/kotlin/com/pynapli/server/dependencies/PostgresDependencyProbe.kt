package com.pynapli.server.dependencies

import com.pynapli.shared.network.dto.DependencyHealthDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PostgresDependencyProbe(
    private val database: Database
) : DependencyProbe {
    override val name: String = "postgres"

    override suspend fun check(): DependencyHealthDto = withContext(Dispatchers.IO) {
        runCatching {
            database.dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT 1").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                    }
                }
            }
        }.fold(
            onSuccess = { DependencyHealthDto(name = name, status = "ok") },
            onFailure = { error ->
                DependencyHealthDto(
                    name = name,
                    status = "failed",
                    detail = error.message ?: error::class.simpleName
                )
            }
        )
    }

    override fun close() {
        // Database lifecycle is owned by ServerDependencies.
    }
}
