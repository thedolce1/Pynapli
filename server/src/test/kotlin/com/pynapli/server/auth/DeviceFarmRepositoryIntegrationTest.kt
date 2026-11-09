package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceFarmRepositoryIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun countsDistinctAccountsForADeviceWithinTheWindowAndIsIdempotent() =
        runBlocking {
            val repository = JdbcDeviceFarmRepository(dataSource)
            val now = Instant.parse("2026-06-15T12:00:00Z")
            val device = "device-hash-A"
            val other = "device-hash-B"
            val users = (1..4).map { createUser() }

            users.forEach { repository.linkDeviceToAccount(device, it, now) }
            // Re-linking the same (device, user) must not double-count.
            repository.linkDeviceToAccount(device, users.first(), now)
            // A separate account on a different device is isolated.
            repository.linkDeviceToAccount(other, createUser(), now)

            assertEquals(4, repository.countDistinctAccountsForDevice(device, now.minus(Duration.ofHours(24))))
            assertEquals(1, repository.countDistinctAccountsForDevice(other, now.minus(Duration.ofHours(24))))
            // No links at/after a future cutoff.
            assertEquals(0, repository.countDistinctAccountsForDevice(device, now.plus(Duration.ofHours(1))))
        }

    @Test
    fun excludesLinksOlderThanTheWindow() =
        runBlocking {
            val repository = JdbcDeviceFarmRepository(dataSource)
            val now = Instant.parse("2026-06-15T12:00:00Z")
            val device = "device-hash-window"

            // One stale link 48h ago, three fresh links now.
            repository.linkDeviceToAccount(device, createUser(), now.minus(Duration.ofHours(48)))
            repeat(3) { repository.linkDeviceToAccount(device, createUser(), now) }

            // The 24h window excludes the stale link.
            assertEquals(3, repository.countDistinctAccountsForDevice(device, now.minus(Duration.ofHours(24))))
        }

    private suspend fun createUser(): UUID {
        val userId = UUID.randomUUID()
        JdbcAuthRepository(dataSource).createEmailUser(
            userId = userId,
            identityId = UUID.randomUUID(),
            emailHash = "email-$userId",
            passwordHash = PasswordHash(hash = "hash", salt = "salt", iterations = 1),
            birthdate = LocalDate.parse("1990-05-13"),
            displayAge = 36,
            adultConfirmedAt = Instant.parse("2026-05-13T12:00:00Z"),
            termsAcceptedAt = Instant.parse("2026-05-13T12:00:00Z"),
            termsVersion = "2026-05-13",
            privacyAcceptedAt = Instant.parse("2026-05-13T12:00:00Z"),
            privacyVersion = "2026-05-13",
        )
        return userId
    }
}
