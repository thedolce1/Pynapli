package com.pynapli.server.account

import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountDataExportProcessorTest {
    private val now = Instant.parse("2026-05-31T12:00:00Z")
    private val userId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val requestId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")

    @Test
    fun processNextPendingBuildsStoresAndMarksReady() =
        runBlocking {
            val repository = FakeRepo(pending = exportRecord("processing"))
            val storage = FakeStorage(StoredExport("https://dl.example/$requestId", now.plusSeconds(600)))
            val processor = AccountDataExportProcessor(repository, FakeArchiver(), storage, Clock.fixed(now, ZoneOffset.UTC))

            assertTrue(processor.processNextPending())

            assertEquals(userId, storage.storedForUser)
            assertEquals(requestId, repository.readyId)
            assertEquals("https://dl.example/$requestId", repository.readyUrl)
            assertEquals(now, repository.readyCompletedAt)
            assertNull(repository.failedId)
        }

    @Test
    fun processNextPendingReturnsFalseWhenNonepending() =
        runBlocking {
            val processor = AccountDataExportProcessor(FakeRepo(pending = null), FakeArchiver(), FakeStorage(), Clock.fixed(now, ZoneOffset.UTC))
            assertFalse(processor.processNextPending())
        }

    @Test
    fun archiveFailureMarksExportFailed() =
        runBlocking {
            val repository = FakeRepo(pending = exportRecord("processing"))
            val failingArchiver =
                object : AccountDataExportArchiver {
                    override suspend fun buildArchive(userId: UUID): ExportArchive = throw IllegalStateException("boom")
                }
            val processor = AccountDataExportProcessor(repository, failingArchiver, FakeStorage(), Clock.fixed(now, ZoneOffset.UTC))

            assertTrue(processor.processNextPending())

            assertEquals(requestId, repository.failedId)
            assertEquals("boom", repository.failedMessage)
            assertNull(repository.readyId)
        }

    private fun exportRecord(status: String) = AccountDataExportRecord(requestId, userId, status, now, null, null, null, null)

    private inner class FakeArchiver : AccountDataExportArchiver {
        override suspend fun buildArchive(userId: UUID): ExportArchive = ExportArchive("export.json", "application/json", "{}".encodeToByteArray())
    }

    private inner class FakeStorage(
        private val stored: StoredExport = StoredExport("https://dl.example/x", Instant.MAX),
    ) : AccountExportStorage {
        var storedForUser: UUID? = null

        override suspend fun store(
            userId: UUID,
            requestId: UUID,
            archive: ExportArchive,
        ): StoredExport {
            storedForUser = userId
            return stored
        }

        override suspend fun read(requestId: UUID): ExportArchive? = null
    }

    private inner class FakeRepo(
        var pending: AccountDataExportRecord?,
    ) : AccountLifecycleRepository {
        var readyId: UUID? = null
        var readyUrl: String? = null
        var readyCompletedAt: Instant? = null
        var failedId: UUID? = null
        var failedMessage: String? = null

        override suspend fun claimNextPendingExport(): AccountDataExportRecord? = pending?.also { pending = null }

        override suspend fun markExportReady(
            requestId: UUID,
            downloadUrl: String,
            completedAt: Instant,
            expiresAt: Instant,
        ): AccountDataExportRecord? {
            readyId = requestId
            readyUrl = downloadUrl
            readyCompletedAt = completedAt
            return null
        }

        override suspend fun markExportFailed(
            requestId: UUID,
            errorMessage: String,
        ): AccountDataExportRecord? {
            failedId = requestId
            failedMessage = errorMessage
            return null
        }

        override suspend fun latestDataExportRequest(userId: UUID): AccountDataExportRecord? = throw NotImplementedError()

        override suspend fun createDataExportRequest(userId: UUID): AccountDataExportRecord = throw NotImplementedError()

        override suspend fun deleteAccount(userId: UUID): AccountDeletionResult? = throw NotImplementedError()

        override suspend fun exportRequestById(requestId: UUID): AccountDataExportRecord? = throw NotImplementedError()
    }
}
