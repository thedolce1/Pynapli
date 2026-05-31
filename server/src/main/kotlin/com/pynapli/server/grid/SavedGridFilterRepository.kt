package com.pynapli.server.grid

import java.time.Instant
import java.util.UUID

interface SavedGridFilterRepository {
    suspend fun list(userId: UUID): List<SavedGridFilterRecord>

    suspend fun create(
        id: UUID,
        userId: UUID,
        name: String,
        payloadJson: String,
    ): SavedGridFilterRecord

    suspend fun delete(
        userId: UUID,
        id: UUID,
    ): Boolean

    suspend fun countForUser(userId: UUID): Int
}

data class SavedGridFilterRecord(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val payloadJson: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
