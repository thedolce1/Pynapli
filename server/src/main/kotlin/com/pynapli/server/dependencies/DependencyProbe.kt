package com.pynapli.server.dependencies

import com.pynapli.shared.network.dto.DependencyHealthDto

interface DependencyProbe : AutoCloseable {
    val name: String

    suspend fun check(): DependencyHealthDto
}
