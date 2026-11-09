package com.pynapli.server.account

import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountLifecycleRepositoryIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource

    @Before
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun createDataExportRequestPersistsLatestRequest() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000911")
            dataSource.insertUserWithProfile(userId, "Maya")
            val repository = JdbcAccountLifecycleRepository(dataSource)

            val created = repository.createDataExportRequest(userId)
            val latest = repository.latestDataExportRequest(userId)

            assertEquals(created.id, latest?.id)
            assertEquals("requested", latest?.status)
            assertEquals(userId, latest?.userId)
        }

    @Test
    fun claimBuildArchiveAndMarkReadyCompletesExport() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000912")
            dataSource.insertUserWithProfile(userId, "Rae")
            val repository = JdbcAccountLifecycleRepository(dataSource)
            val archiver = JdbcAccountDataExportArchiver(dataSource)

            repository.createDataExportRequest(userId)
            val claimed = repository.claimNextPendingExport(STALE_AFTER, MAX_ATTEMPTS)
            assertNotNull(claimed)
            assertEquals("processing", claimed.status)

            // Exercises the archiver SQL against the real schema (profiles/discovery_settings/messages/activity_events).
            val archive = archiver.buildArchive(claimed.userId)
            val json = archive.bytes.decodeToString()
            assertTrue(json.contains(userId.toString()), "archive should include the user id")

            val ready =
                repository.markExportReady(
                    requestId = claimed.id,
                    downloadUrl = "https://dl.example/${claimed.id}",
                    completedAt = Instant.now(),
                    expiresAt = Instant.now().plusSeconds(600),
                )
            assertEquals("ready", ready?.status)
            assertEquals("https://dl.example/${claimed.id}", ready?.downloadUrl)

            // Nothing left to claim.
            assertNull(repository.claimNextPendingExport(STALE_AFTER, MAX_ATTEMPTS))
        }

    // Regression: a worker that died mid-export left the request in 'processing' forever.
    @Test
    fun staleProcessingClaimIsReclaimedButAFreshOneIsNot() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000913")
            dataSource.insertUserWithProfile(userId, "Ilse")
            val repository = JdbcAccountLifecycleRepository(dataSource)
            val request = repository.createDataExportRequest(userId)

            val first = assertNotNull(repository.claimNextPendingExport(STALE_AFTER, MAX_ATTEMPTS))
            assertEquals(request.id, first.id)
            // A live claim (just started) must not be taken over.
            assertNull(repository.claimNextPendingExport(STALE_AFTER, MAX_ATTEMPTS))

            dataSource.ageExportClaim(request.id, STALE_AFTER.plusMinutes(1))
            val reclaimed = assertNotNull(repository.claimNextPendingExport(STALE_AFTER, MAX_ATTEMPTS))
            assertEquals(request.id, reclaimed.id)
            assertEquals("processing", reclaimed.status)
            assertEquals(2, dataSource.exportAttempts(request.id))
        }

    @Test
    fun exportOutOfAttemptsIsFailedInsteadOfRetried() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000914")
            dataSource.insertUserWithProfile(userId, "Oren")
            val repository = JdbcAccountLifecycleRepository(dataSource)
            val request = repository.createDataExportRequest(userId)

            repeat(MAX_ATTEMPTS) {
                assertNotNull(repository.claimNextPendingExport(STALE_AFTER, MAX_ATTEMPTS))
                dataSource.ageExportClaim(request.id, STALE_AFTER.plusMinutes(1))
            }
            assertNull(repository.claimNextPendingExport(STALE_AFTER, MAX_ATTEMPTS), "no attempts left")

            assertEquals(1, repository.failAbandonedExports(STALE_AFTER, MAX_ATTEMPTS, "gave up"))
            val failed = assertNotNull(repository.exportRequestById(request.id))
            assertEquals("failed", failed.status)
            assertEquals("gave up", failed.errorMessage)
        }

    @Test
    fun deleteAccountRemovesDiscoveryRevokesSessionsAndPreservesSafetyReports() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000921")
            val reporterId = UUID.fromString("00000000-0000-0000-0000-000000000922")
            dataSource.insertUserWithProfile(userId, "Maya")
            dataSource.insertUserWithProfile(reporterId, "Reporter")
            dataSource.insertActiveSession(userId)
            dataSource.insertActiveTravelLocation(userId)
            dataSource.insertCurrentLocation(userId)
            dataSource.insertMediaAsset(userId)
            dataSource.insertActivePartnerLinkWithBenefit(subscriberId = userId, beneficiaryId = reporterId)
            dataSource.insertSafetyReport(reporterId, userId)
            val repository = JdbcAccountLifecycleRepository(dataSource)

            val result = repository.deleteAccount(userId)

            // Discovery removal + session revoke (existing behavior).
            assertEquals(1, result?.revokedSessionCount)
            assertEquals("deleted", dataSource.stringValue("SELECT status FROM users WHERE id = ?", userId))
            assertNotNull(dataSource.timestampValue("SELECT deleted_at FROM users WHERE id = ?", userId))
            assertEquals("hidden", dataSource.stringValue("SELECT visibility_status FROM profiles WHERE user_id = ?", userId))
            assertEquals(false, dataSource.booleanValue("SELECT grid_visible FROM discovery_settings WHERE user_id = ?", userId))
            assertEquals(false, dataSource.booleanValue("SELECT swipe_visible FROM discovery_settings WHERE user_id = ?", userId))
            assertEquals(false, dataSource.booleanValue("SELECT events_visible FROM discovery_settings WHERE user_id = ?", userId))
            assertEquals("cleared", dataSource.stringValue("SELECT status FROM travel_locations WHERE user_id = ?", userId))
            assertNotNull(dataSource.timestampValue("SELECT revoked_at FROM user_sessions WHERE user_id = ?", userId))

            // GDPR/CCPA erasure: identity + profile PII scrubbed.
            assertNull(dataSource.stringValue("SELECT email_hash FROM users WHERE id = ?", userId))
            assertNull(dataSource.stringValue("SELECT password_hash FROM auth_identities WHERE user_id = ?", userId))
            assertEquals(
                "deleted:",
                dataSource.stringValue("SELECT left(provider_subject, 8) FROM auth_identities WHERE user_id = ?", userId),
            )
            assertNull(dataSource.stringValue("SELECT display_name FROM profiles WHERE user_id = ?", userId))
            assertNull(dataSource.stringValue("SELECT bio FROM profiles WHERE user_id = ?", userId))
            assertNull(dataSource.stringValue("SELECT neighborhood FROM profiles WHERE user_id = ?", userId))
            assertNull(dataSource.timestampValue("SELECT birthdate FROM profiles WHERE user_id = ?", userId))
            assertNull(dataSource.stringValue("SELECT gender_detail FROM profiles WHERE user_id = ?", userId))
            assertNull(dataSource.stringValue("SELECT orientation_detail FROM profiles WHERE user_id = ?", userId))
            assertNull(dataSource.stringValue("SELECT lifestyle_tags::text FROM profiles WHERE user_id = ?", userId))
            assertNull(dataSource.stringValue("SELECT logistics_tags::text FROM profiles WHERE user_id = ?", userId))
            assertNull(dataSource.stringValue("SELECT intents::text FROM profiles WHERE user_id = ?", userId))

            // Exact location purged; media soft-deleted; partner link + benefit ended.
            assertEquals(0, dataSource.intValue("SELECT count(*) FROM user_locations WHERE user_id = ?", userId))
            assertEquals(1, result?.purgedLocationCount)
            assertNotNull(dataSource.timestampValue("SELECT deleted_at FROM media_assets WHERE owner_user_id = ?", userId))
            assertEquals(1, result?.softDeletedMediaCount)
            assertEquals("unlinked", dataSource.stringValue("SELECT status FROM user_profile_links WHERE user_id = ?", userId))
            assertEquals(1, result?.endedPartnerLinkCount)
            assertEquals("ended", dataSource.stringValue("SELECT status FROM linked_partner_benefits WHERE subscriber_user_id = ?", userId))
            assertEquals(1, result?.endedPartnerBenefitCount)

            // Safety/legal records preserved.
            assertEquals(1, dataSource.intValue("SELECT count(*) FROM safety_reports WHERE reported_user_id = ?", userId))
            assertEquals(1, dataSource.intValue("SELECT count(*) FROM account_deletion_events WHERE user_id = ?", userId))
        }

    private fun DataSource.ageExportClaim(
        requestId: UUID,
        age: Duration,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE account_data_export_requests SET processing_started_at = now() - make_interval(secs => ?) WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, age.seconds)
                    statement.setObject(2, requestId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.exportAttempts(requestId: UUID): Int =
        connection.use { connection ->
            connection.prepareStatement("SELECT attempts FROM account_data_export_requests WHERE id = ?").use { statement ->
                statement.setObject(1, requestId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private companion object {
        val STALE_AFTER: Duration = Duration.ofMinutes(15)
        const val MAX_ATTEMPTS = 3
    }

    private fun DataSource.insertUserWithProfile(
        userId: UUID,
        displayName: String,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO users (id, email_hash, status)
                    VALUES (?, ?, 'active')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, "email-hash-$userId")
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO auth_identities (id, user_id, provider, provider_subject, password_hash, password_salt, password_iterations)
                    VALUES (?, ?, 'email', ?, 'pw-hash', 'pw-salt', 100000)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, userId)
                    statement.setString(3, "subject-$userId")
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO profiles (
                        user_id, display_name, bio, neighborhood, birthdate, gender, gender_detail, orientation, orientation_detail,
                        lifestyle_tags, logistics_tags, intents, moderation_status, visibility_status
                    )
                    VALUES (
                        ?, ?, 'My bio', 'Mission', DATE '1990-01-01', 'self_describe', 'Genderfluid', 'queer', NULL,
                        ARRAY['enm']::text[], ARRAY['hosts']::text[], ARRAY['events']::text[], 'approved', 'visible'
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, displayName)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO discovery_settings (user_id, grid_visible, swipe_visible, events_visible)
                    VALUES (?, true, true, true)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertActiveSession(userId: UUID) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO user_sessions (
                        id,
                        user_id,
                        access_token_hash,
                        refresh_token_hash,
                        access_expires_at,
                        refresh_expires_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, userId)
                    statement.setString(3, "access-$userId")
                    statement.setString(4, "refresh-$userId")
                    statement.setTimestamp(5, Timestamp.from(Instant.parse("2026-05-26T13:00:00Z")))
                    statement.setTimestamp(6, Timestamp.from(Instant.parse("2026-06-26T12:00:00Z")))
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertActiveTravelLocation(userId: UUID) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO travel_locations (id, user_id, city, region, country, geom, status)
                    VALUES (?, ?, 'Toronto', 'ON', 'CA', ST_SetSRID(ST_MakePoint(-79.3832, 43.6532), 4326), 'active')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, userId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertSafetyReport(
        reporterId: UUID,
        reportedId: UUID,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO safety_reports (
                        id,
                        reporter_user_id,
                        reported_user_id,
                        target_type,
                        category,
                        status
                    )
                    VALUES (?, ?, ?, 'profile', 'spam_or_fake_profile', 'open')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, reporterId)
                    statement.setObject(3, reportedId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertCurrentLocation(userId: UUID) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO user_locations (user_id, geom, geohash, city, location_source)
                    VALUES (?, ST_SetSRID(ST_MakePoint(-79.3832, 43.6532), 4326), 'dpz8', 'Toronto', 'gps')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertMediaAsset(userId: UUID) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO media_assets (
                        id, owner_user_id, media_type, storage_provider, storage_bucket,
                        storage_object_key, content_type, byte_size, processing_status, moderation_status
                    )
                    VALUES (?, ?, 'image', 'gcs', 'pynapli-local-media', ?, 'image/jpeg', 1024, 'ready', 'approved')
                    """.trimIndent(),
                ).use { statement ->
                    val assetId = UUID.randomUUID()
                    statement.setObject(1, assetId)
                    statement.setObject(2, userId)
                    statement.setString(3, "media/$assetId")
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertActivePartnerLinkWithBenefit(
        subscriberId: UUID,
        beneficiaryId: UUID,
    ) {
        connection.use { connection ->
            val linkId = UUID.randomUUID()
            connection
                .prepareStatement(
                    """
                    INSERT INTO user_profile_links (id, user_id, linked_user_id, status, accepted_at)
                    VALUES (?, ?, ?, 'active', now())
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, linkId)
                    statement.setObject(2, subscriberId)
                    statement.setObject(3, beneficiaryId)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO linked_partner_benefits (id, subscriber_user_id, beneficiary_user_id, partner_link_id, tier, status)
                    VALUES (?, ?, ?, ?, 'select', 'active')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, subscriberId)
                    statement.setObject(3, beneficiaryId)
                    statement.setObject(4, linkId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.stringValue(
        sql: String,
        userId: UUID,
    ): String? = queryValue(sql, userId) { getString(1) }

    private fun DataSource.booleanValue(
        sql: String,
        userId: UUID,
    ): Boolean? = queryValue(sql, userId) { getBoolean(1) }

    private fun DataSource.timestampValue(
        sql: String,
        userId: UUID,
    ): Timestamp? = queryValue(sql, userId) { getTimestamp(1) }

    private fun DataSource.intValue(
        sql: String,
        userId: UUID,
    ): Int = queryValue(sql, userId) { getInt(1) } ?: 0

    private fun <T> DataSource.queryValue(
        sql: String,
        userId: UUID,
        read: java.sql.ResultSet.() -> T,
    ): T? =
        connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.read() else null
                }
            }
        }
}
