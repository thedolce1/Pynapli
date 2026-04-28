package com.pynapli.server.safety

import com.pynapli.shared.network.dto.BlockSummaryDto
import com.pynapli.shared.network.dto.BlocksListDto
import java.util.UUID

class BlockService(
    private val repository: BlockRepository
) {
    suspend fun blockUser(blockerUserId: UUID, targetUserIdText: String, reason: String?): BlockSummaryDto {
        val targetUserId = targetUserIdText.toUuidOrThrow()
        if (targetUserId == blockerUserId) throw SafetyException("BLOCK_SELF_FORBIDDEN")

        val cleanedReason = reason?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > MAX_REASON_LENGTH) throw SafetyException("BLOCK_REASON_TOO_LONG")
        }

        val record = repository.block(blockerUserId, targetUserId, cleanedReason)
        return BlockSummaryDto(
            blockedUserId = record.blockedUserId.toString(),
            blockedDisplayName = null,
            reason = record.reason,
            createdAt = record.createdAt.toString()
        )
    }

    suspend fun unblockUser(blockerUserId: UUID, targetUserIdText: String): Boolean {
        val targetUserId = targetUserIdText.toUuidOrThrow()
        if (targetUserId == blockerUserId) throw SafetyException("BLOCK_SELF_FORBIDDEN")
        return repository.unblock(blockerUserId, targetUserId)
    }

    suspend fun listBlocks(blockerUserId: UUID): BlocksListDto =
        BlocksListDto(
            blocks = repository.listBlocks(blockerUserId).map { record ->
                BlockSummaryDto(
                    blockedUserId = record.blockedUserId.toString(),
                    blockedDisplayName = record.blockedDisplayName,
                    reason = record.reason,
                    createdAt = record.createdAt.toString()
                )
            }
        )

    private fun String.toUuidOrThrow(): UUID =
        runCatching { UUID.fromString(this) }.getOrElse { throw SafetyException("INVALID_BLOCK_TARGET") }

    companion object {
        private const val MAX_REASON_LENGTH = 500
    }
}

class SafetyException(val code: String) : RuntimeException(code)
