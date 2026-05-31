package com.pynapli.server.account

import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccountLifecycleServiceTest {
    @Test
    fun requestDataExportReturnsRequestedStatusMessage() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000901")
            val repository = FakeAccountLifecycleRepository()
            val service = AccountLifecycleService(repository)

            val result = service.requestDataExport(userId)

            assertEquals("requested", result.status)
            assertEquals("Your data export request has been received.", result.message)
            assertEquals(userId, repository.lastExportUserId)
        }

    @Test
    fun deleteAccountRequiresExplicitConfirmation() =
        runBlocking {
            val repository = FakeAccountLifecycleRepository()
            val service = AccountLifecycleService(repository)

            val error =
                assertFailsWith<AccountLifecycleException> {
                    service.deleteAccount(UUID.randomUUID(), "delete")
                }

            assertEquals("INVALID_ACCOUNT_DELETE_CONFIRMATION", error.code)
            assertEquals(null, repository.lastDeletedUserId)
        }

    @Test
    fun deleteAccountReturnsRevokedSessionCount() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000902")
            val repository =
                FakeAccountLifecycleRepository(
                    deleteResult = AccountDeletionResult(userId = userId, revokedSessionCount = 3),
                )
            val service = AccountLifecycleService(repository)

            val result = service.deleteAccount(userId, "DELETE")

            assertEquals(true, result.deleted)
            assertEquals(3, result.revokedSessionCount)
            assertEquals(userId, repository.lastDeletedUserId)
        }

    @Test
    fun downloadExportReturnsReadyArchiveToItsOwner() =
        runBlocking {
            val archive = downloadService().downloadExport(EXPORT_OWNER, READY_EXPORT_ID.toString())

            assertEquals("archive", archive.bytes.decodeToString())
        }

    @Test
    fun downloadExportHidesAnotherUsersExport() =
        runBlocking {
            val error =
                assertFailsWith<AccountLifecycleException> {
                    downloadService().downloadExport(UUID.randomUUID(), READY_EXPORT_ID.toString())
                }

            assertEquals("EXPORT_NOT_FOUND", error.code)
        }

    @Test
    fun downloadExportRefusesAnExpiredLink() =
        runBlocking {
            val afterExpiry = Clock.fixed(EXPORT_EXPIRES_AT, ZoneOffset.UTC)
            val error =
                assertFailsWith<AccountLifecycleException> {
                    downloadService(afterExpiry).downloadExport(EXPORT_OWNER, READY_EXPORT_ID.toString())
                }

            assertEquals("EXPORT_NOT_FOUND", error.code)
        }

    private fun downloadService(clock: Clock = Clock.fixed(EXPORT_EXPIRES_AT.minusSeconds(60), ZoneOffset.UTC)) =
        AccountLifecycleService(
            repository = FakeAccountLifecycleRepository(exportById = readyExport()),
            exportStorage = InMemoryExportStorage(),
            clock = clock,
        )

    private fun readyExport() =
        AccountDataExportRecord(
            id = READY_EXPORT_ID,
            userId = EXPORT_OWNER,
            status = "ready",
            requestedAt = Instant.parse("2026-05-26T12:00:00Z"),
            completedAt = Instant.parse("2026-05-26T12:01:00Z"),
            expiresAt = EXPORT_EXPIRES_AT,
            downloadUrl = "http://localhost/v1/account/data-export/$READY_EXPORT_ID/download",
            errorMessage = null,
        )

    private companion object {
        val EXPORT_OWNER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000903")
        val READY_EXPORT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000904")
        val EXPORT_EXPIRES_AT: Instant = Instant.parse("2026-06-02T12:01:00Z")
    }
}

private class InMemoryExportStorage : AccountExportStorage {
    override suspend fun store(
        userId: UUID,
        requestId: UUID,
        archive: ExportArchive,
    ): StoredExport = error("not used")

    override suspend fun read(requestId: UUID): ExportArchive = ExportArchive("export.json", "application/json", "archive".encodeToByteArray())
}

private class FakeAccountLifecycleRepository(
    private val deleteResult: AccountDeletionResult? =
        AccountDeletionResult(
            userId = UUID.fromString("00000000-0000-0000-0000-000000000999"),
            revokedSessionCount = 1,
        ),
    private val exportById: AccountDataExportRecord? = null,
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
            errorMessage = null,
        )
    }

    override suspend fun deleteAccount(userId: UUID): AccountDeletionResult? {
        lastDeletedUserId = userId
        return deleteResult?.copy(userId = userId)
    }

    override suspend fun claimNextPendingExport(): AccountDataExportRecord? = null

    override suspend fun markExportReady(
        requestId: UUID,
        downloadUrl: String,
        completedAt: Instant,
        expiresAt: Instant,
    ): AccountDataExportRecord? = null

    override suspend fun markExportFailed(
        requestId: UUID,
        errorMessage: String,
    ): AccountDataExportRecord? = null

    override suspend fun exportRequestById(requestId: UUID): AccountDataExportRecord? = exportById?.takeIf { it.id == requestId }
}
