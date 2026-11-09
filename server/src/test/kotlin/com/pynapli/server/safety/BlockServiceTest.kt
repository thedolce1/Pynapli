package com.pynapli.server.safety

import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockServiceTest {
    @Test
    fun blockUserCreatesBlock() =
        runBlocking {
            val blockerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val repository = InMemoryBlockRepository()
            val service = BlockService(repository)

            val summary = service.blockUser(blockerId, targetId.toString(), reason = "Spam")

            assertEquals(targetId.toString(), summary.blockedUserId)
            assertEquals("Spam", summary.reason)
            assertTrue(repository.isBlockedEitherWay(blockerId, targetId))
            assertTrue(repository.isBlockedEitherWay(targetId, blockerId))
        }

    @Test
    fun blockUserRejectsSelfBlock() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000010")
            val service = BlockService(InMemoryBlockRepository())

            val error =
                assertFailsWith<SafetyException> {
                    service.blockUser(viewerId, viewerId.toString(), reason = null)
                }

            assertEquals("BLOCK_SELF_FORBIDDEN", error.code)
        }

    @Test
    fun blockUserRejectsInvalidUuid() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000020")
            val service = BlockService(InMemoryBlockRepository())

            val error =
                assertFailsWith<SafetyException> {
                    service.blockUser(viewerId, "not-a-uuid", reason = null)
                }

            assertEquals("INVALID_BLOCK_TARGET", error.code)
        }

    @Test
    fun blockUserRejectsOversizedReason() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000030")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000031")
            val service = BlockService(InMemoryBlockRepository())

            val error =
                assertFailsWith<SafetyException> {
                    service.blockUser(viewerId, targetId.toString(), reason = "x".repeat(501))
                }

            assertEquals("BLOCK_REASON_TOO_LONG", error.code)
        }

    @Test
    fun unblockUserRemovesActiveBlock() =
        runBlocking {
            val blockerId = UUID.fromString("00000000-0000-0000-0000-000000000040")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000041")
            val repository = InMemoryBlockRepository()
            val service = BlockService(repository)
            service.blockUser(blockerId, targetId.toString(), reason = null)

            val removed = service.unblockUser(blockerId, targetId.toString())

            assertTrue(removed)
            assertFalse(repository.isBlockedEitherWay(blockerId, targetId))
        }

    @Test
    fun listBlocksReturnsActiveBlocksWithDisplayName() =
        runBlocking {
            val blockerId = UUID.fromString("00000000-0000-0000-0000-000000000050")
            val targetA = UUID.fromString("00000000-0000-0000-0000-000000000051")
            val targetB = UUID.fromString("00000000-0000-0000-0000-000000000052")
            val repository =
                InMemoryBlockRepository().apply {
                    setDisplayName(targetA, "Alex")
                }
            val service = BlockService(repository)
            service.blockUser(blockerId, targetA.toString(), reason = null)
            service.blockUser(blockerId, targetB.toString(), reason = "Annoying")

            val response = service.listBlocks(blockerId)

            assertEquals(2, response.blocks.size)
            val byId = response.blocks.associateBy { it.blockedUserId }
            assertEquals("Alex", byId.getValue(targetA.toString()).blockedDisplayName)
            assertEquals(null, byId.getValue(targetB.toString()).blockedDisplayName)
            assertEquals("Annoying", byId.getValue(targetB.toString()).reason)
        }

    @Test
    fun isBlockedEitherWayDetectsBothDirections() =
        runBlocking {
            val a = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
            val b = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
            val repository = InMemoryBlockRepository()
            repository.block(a, b, reason = null)

            assertTrue(repository.isBlockedEitherWay(a, b))
            assertTrue(repository.isBlockedEitherWay(b, a))
        }
}

class InMemoryBlockRepository : BlockRepository {
    private val records = mutableMapOf<Pair<UUID, UUID>, BlockRecord>()
    private val displayNames = mutableMapOf<UUID, String>()
    private val now = Instant.parse("2026-04-28T18:00:00Z")

    override suspend fun block(
        blockerUserId: UUID,
        blockedUserId: UUID,
        reason: String?,
        blockIdFactory: () -> UUID,
    ): BlockRecord {
        val key = blockerUserId to blockedUserId
        val record =
            records[key]?.copy(reason = reason ?: records[key]?.reason)
                ?: BlockRecord(
                    id = blockIdFactory(),
                    blockerUserId = blockerUserId,
                    blockedUserId = blockedUserId,
                    reason = reason,
                    createdAt = now,
                )
        records[key] = record
        return record
    }

    override suspend fun unblock(
        blockerUserId: UUID,
        blockedUserId: UUID,
    ): Boolean = records.remove(blockerUserId to blockedUserId) != null

    override suspend fun listBlocks(blockerUserId: UUID): List<BlockSummaryRecord> =
        records.values
            .filter { it.blockerUserId == blockerUserId }
            .sortedWith(compareByDescending<BlockRecord> { it.createdAt }.thenBy { it.blockedUserId })
            .map { record ->
                BlockSummaryRecord(
                    blockedUserId = record.blockedUserId,
                    blockedDisplayName = displayNames[record.blockedUserId],
                    reason = record.reason,
                    createdAt = record.createdAt,
                )
            }

    override suspend fun isBlockedEitherWay(
        userIdA: UUID,
        userIdB: UUID,
    ): Boolean = records.containsKey(userIdA to userIdB) || records.containsKey(userIdB to userIdA)

    override suspend fun listBlockedRelatedUserIds(userId: UUID): Set<UUID> =
        buildSet {
            records.values.forEach { record ->
                when (userId) {
                    record.blockerUserId -> add(record.blockedUserId)
                    record.blockedUserId -> add(record.blockerUserId)
                }
            }
        }

    fun setDisplayName(
        userId: UUID,
        displayName: String,
    ) {
        displayNames[userId] = displayName
    }
}
