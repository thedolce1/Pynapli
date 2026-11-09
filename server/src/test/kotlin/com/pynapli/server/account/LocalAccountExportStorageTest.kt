package com.pynapli.server.account

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalAccountExportStorageTest {
    private val directory = Files.createTempDirectory("pynapli-export-test")
    private val storage = LocalAccountExportStorage(directory = directory, downloadBaseUrl = "http://10.0.2.2:8080/")
    private val archive = ExportArchive("export.json", "application/json", "{}".encodeToByteArray())

    @Test
    fun linkCarriesARandomKeyThatReadsTheArchiveBack() =
        runBlocking {
            val requestId = UUID.randomUUID()
            val stored = storage.store(UUID.randomUUID(), requestId, archive)

            val prefix = "http://10.0.2.2:8080/v1/account/data-export/$requestId/download?key="
            assertTrue(stored.downloadUrl.startsWith(prefix), stored.downloadUrl)
            val key = stored.downloadUrl.removePrefix(prefix)
            assertContentEquals(archive.bytes, storage.read(requestId, key)?.bytes)

            val other = storage.store(UUID.randomUUID(), UUID.randomUUID(), archive)
            assertNotEquals(key, other.downloadUrl.substringAfter("key="), "keys must be random per export")
        }

    @Test
    fun wrongOrMalformedKeyReadsNothing() =
        runBlocking {
            val requestId = UUID.randomUUID()
            storage.store(UUID.randomUUID(), requestId, archive)

            assertNull(storage.read(requestId, "0".repeat(64)))
            assertNull(storage.read(requestId, ""))
            // The key becomes part of a file path, so traversal-shaped input must be refused outright.
            assertNull(storage.read(requestId, "../../etc/passwd"))
        }
}
