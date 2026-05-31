package com.pynapli.server.swipe

import java.time.Instant
import java.util.UUID

interface SavedSwipeFilterRepository {
    suspend fun list(userId: UUID): List<SavedSwipeFilterRecord>

    suspend fun create(
        id: UUID,
        userId: UUID,
        name: String,
        payloadJson: String,
    ): SavedSwipeFilterRecord

    suspend fun delete(
        userId: UUID,
        id: UUID,
    ): Boolean

    suspend fun countForUser(userId: UUID): Int
}

data class SavedSwipeFilterRecord(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val payloadJson: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
