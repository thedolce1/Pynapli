package com.pynapli.server.account

import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccountLifecycleServiceTest {
    @Test
    fun requestDataExportReturnsRequestedStatusMessage() = runBlocking {
        val userId = UUID.fromString("00000000-0000-0000-0000-000000000901")
        val repository = FakeAccountLifecycleRepository()
        val service = AccountLifecycleService(repository)

        val result = service.requestDataExport(userId)

        assertEquals("requested", result.status)
        assertEquals("Your data export request has been received.", result.message)
        assertEquals(userId, repository.lastExportUserId)
    }

    @Test
    fun deleteAccountRequiresExplicitConfirmation() = runBlocking {
        val repository = FakeAccountLifecycleRepository()
        val service = AccountLifecycleService(repository)

        val error = assertFailsWith<AccountLifecycleException> {
            service.deleteAccount(UUID.randomUUID(), "delete")
        }

        assertEquals("INVALID_ACCOUNT_DELETE_CONFIRMATION", error.code)
        assertEquals(null, repository.lastDeletedUserId)
    }

    @Test
    fun deleteAccountReturnsRevokedSessionCount() = runBlocking {
        val userId = UUID.fromString("00000000-0000-0000-0000-000000000902")
        val repository = FakeAccountLifecycleRepository(
            deleteResult = AccountDeletionResult(userId = userId, revokedSessionCount = 3)
        )
        val service = AccountLifecycleService(repository)

        val result = service.deleteAccount(userId, "DELETE")

        assertEquals(true, result.deleted)
        assertEquals(3, result.revokedSessionCount)
        assertEquals(userId, repository.lastDeletedUserId)
    }
}

private class FakeAccountLifecycleRepository(
    private val deleteResult: AccountDeletionResult? = AccountDeletionResult(
        userId = UUID.fromString("00000000-0000-0000-0000-000000000999"),
        revokedSessionCount = 1
    )
) : AccountLifecycleRepository {
    var lastExportUserId: UUID? = null
        private set
    var lastDeletedUserId: UUID? = null
        private set

    override suspend fun latestDataExportRequest(userId: UUID): AccountDataExportRecord? = null

    override suspend fun createDataExportRequest(userId: UUID): AccountDataExportRecord {
        lastExportUserId = userId
        return AccountDataExportRecord(
            id = UUID.fromString("00000000-0000-0000-0000-000000000998"),
            userId = userId,
            status = "requested",
            requestedAt = Instant.parse("2026-05-26T12:00:00Z"),
            completedAt = null,
            expiresAt = null,
            downloadUrl = null,
            errorMessage = null
        )
    }

    override suspend fun deleteAccount(userId: UUID): AccountDeletionResult? {
        lastDeletedUserId = userId
        return deleteResult?.copy(userId = userId)
    }
}
