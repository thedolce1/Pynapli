package com.pynapli.server.safety

import java.time.Instant
import java.util.UUID

interface BlockRepository {
    suspend fun block(
        blockerUserId: UUID,
        blockedUserId: UUID,
        reason: String?,
        blockIdFactory: () -> UUID = UUID::randomUUID,
    ): BlockRecord

    suspend fun unblock(
        blockerUserId: UUID,
        blockedUserId: UUID,
    ): Boolean

    suspend fun listBlocks(blockerUserId: UUID): List<BlockSummaryRecord>

    suspend fun isBlockedEitherWay(
        userIdA: UUID,
        userIdB: UUID,
    ): Boolean

    suspend fun listBlockedRelatedUserIds(userId: UUID): Set<UUID>
}

data class BlockRecord(
    val id: UUID,
    val blockerUserId: UUID,
    val blockedUserId: UUID,
    val reason: String?,
    val createdAt: Instant,
)

data class BlockSummaryRecord(
    val blockedUserId: UUID,
    val blockedDisplayName: String?,
    val reason: String?,
    val createdAt: Instant,
)
