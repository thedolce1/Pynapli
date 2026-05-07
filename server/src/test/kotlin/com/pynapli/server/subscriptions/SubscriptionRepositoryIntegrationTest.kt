package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.SubscriptionTier
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionRepositoryIntegrationTest {
    private lateinit var dataSource: HikariDataSource

    @Before
    fun setUp() {
        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
                poolName = "pynapli-subscription-test"
            }
        )

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
            .also { flyway ->
                flyway.clean()
                flyway.migrate()
            }
    }

    @After
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun migrationsCreateSubscriptionFoundationTables() {
        assertTrue(dataSource.tableExists("subscriptions"))
        assertTrue(dataSource.tableExists("entitlements"))
        assertTrue(dataSource.tableExists("linked_partner_benefits"))
        assertTrue(dataSource.tableExists("premium_inventory"))
        assertTrue(dataSource.columnExists("subscriptions", "external_product_id"))
        assertTrue(dataSource.columnExists("subscriptions", "last_verified_at"))
        assertTrue(dataSource.indexExists("idx_subscriptions_user_active"))
        assertTrue(dataSource.indexExists("idx_subscriptions_play_store_product"))
        assertTrue(dataSource.indexExists("idx_linked_partner_benefits_beneficiary_active"))
        assertTrue(dataSource.indexExists("idx_premium_inventory_user_available"))
    }

    @Test
    fun ownActiveSubscriptionReturnsHighestPaidTier() = runBlocking {
        val user = uuid(101)
        val now = Instant.parse("2026-05-06T12:00:00Z")
        dataSource.insertUser(user)
        dataSource.insertSubscription(
            userId = user,
            tier = SubscriptionTier.SOCIAL,
            externalId = "social-active",
            startsAt = Instant.parse("2026-05-01T00:00:00Z"),
            endsAt = Instant.parse("2026-06-01T00:00:00Z")
        )
        dataSource.insertSubscription(
            userId = user,
            tier = SubscriptionTier.SELECT,
            externalId = "select-active",
            startsAt = Instant.parse("2026-05-01T00:00:00Z"),
            endsAt = Instant.parse("2026-06-01T00:00:00Z")
        )
        dataSource.insertSubscription(
            userId = user,
            tier = SubscriptionTier.SELECT,
            externalId = "select-expired",
            status = "expired",
            startsAt = Instant.parse("2026-04-01T00:00:00Z"),
            endsAt = Instant.parse("2026-05-01T00:00:00Z")
        )

        val subscription = JdbcSubscriptionRepository(dataSource).findBestActiveOwnSubscription(user, now)

        assertNotNull(subscription)
        assertEquals(SubscriptionTier.SELECT, subscription.tier)
        assertEquals("active", subscription.status)
    }

    @Test
    fun linkedPartnerSubscriptionRequiresActiveLinkAndActiveSubscription() = runBlocking {
        val viewer = uuid(201)
        val partner = uuid(202)
        val now = Instant.parse("2026-05-06T12:00:00Z")
        dataSource.insertUser(viewer)
        dataSource.insertUser(partner)
        dataSource.insertSubscription(
            userId = partner,
            tier = SubscriptionTier.SELECT,
            externalId = "partner-select",
            startsAt = Instant.parse("2026-05-01T00:00:00Z"),
            endsAt = Instant.parse("2026-06-01T00:00:00Z")
        )
        val repository = JdbcSubscriptionRepository(dataSource)

        assertNull(repository.findBestActiveLinkedPartnerSubscription(viewer, now))

        dataSource.insertActiveProfileLink(viewer, partner)
        val benefit = repository.findBestActiveLinkedPartnerSubscription(viewer, now)

        assertNotNull(benefit)
        assertEquals(partner, benefit.linkedPartnerUserId)
        assertEquals(SubscriptionTier.SELECT, benefit.subscription.tier)
    }

    @Test
    fun storedEntitlementsReturnBestActiveRecord() = runBlocking {
        val user = uuid(301)
        val now = Instant.parse("2026-05-06T12:00:00Z")
        dataSource.insertUser(user)
        dataSource.insertEntitlement(
            userId = user,
            tier = SubscriptionTier.SOCIAL,
            source = EntitlementSource.PROMO,
            startsAt = Instant.parse("2026-05-01T00:00:00Z"),
            endsAt = Instant.parse("2026-06-01T00:00:00Z")
        )
        dataSource.insertEntitlement(
            userId = user,
            tier = SubscriptionTier.SELECT,
            source = EntitlementSource.ADMIN,
            startsAt = Instant.parse("2026-05-01T00:00:00Z"),
            endsAt = null
        )
        dataSource.insertEntitlement(
            userId = user,
            tier = SubscriptionTier.SELECT,
            source = EntitlementSource.PROMO,
            status = "expired",
            startsAt = Instant.parse("2026-04-01T00:00:00Z"),
            endsAt = Instant.parse("2026-05-01T00:00:00Z")
        )

        val entitlement = JdbcSubscriptionRepository(dataSource).findBestActiveStoredEntitlement(user, now)

        assertNotNull(entitlement)
        assertEquals(SubscriptionTier.SELECT, entitlement.tier)
        assertEquals(EntitlementSource.ADMIN, entitlement.source)
    }

    @Test
    fun consumeIntroNoteInventoryDecrementsOneAvailableUnit() = runBlocking {
        val user = uuid(401)
        val now = Instant.parse("2026-05-06T12:00:00Z")
        dataSource.insertUser(user)
        dataSource.insertPremiumInventory(
            userId = user,
            inventoryType = "intro_note",
            quantity = 1,
            expiresAt = Instant.parse("2026-06-01T00:00:00Z")
        )
        val repository = JdbcSubscriptionRepository(dataSource)

        assertEquals(true, repository.consumeIntroNoteInventory(user, now))
        assertEquals(false, repository.consumeIntroNoteInventory(user, now))
        assertEquals(0, dataSource.inventoryQuantity(user, "intro_note"))
    }

    @Test
    fun verifiedPlayStorePurchasePersistsMetadataAndActivatesSubscription() = runBlocking {
        val user = uuid(501)
        val now = Instant.parse("2026-05-06T12:00:00Z")
        val purchaseToken = "play-store-token-501"
        dataSource.insertUser(user)
        val repository = JdbcSubscriptionRepository(dataSource)

        val record = repository.upsertVerifiedPlayStoreSubscriptionPurchase(
            userId = user,
            productId = "pynapli_select_monthly",
            purchaseToken = purchaseToken,
            orderId = "GPA.501",
            tier = SubscriptionTier.SELECT,
            status = "active",
            externalStatus = "SUBSCRIPTION_STATE_ACTIVE",
            acknowledgementState = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
            acknowledgedAt = now,
            currentPeriodStart = now,
            currentPeriodEnd = Instant.parse("2026-06-06T12:00:00Z"),
            verifiedAt = now
        )
        val active = repository.findBestActiveOwnSubscription(user, now.plusSeconds(1))

        assertEquals("active", record.status)
        assertNotNull(active)
        assertEquals(SubscriptionTier.SELECT, active.tier)
        val metadata = dataSource.subscriptionMetadata(purchaseToken)
        assertEquals("pynapli_select_monthly", metadata["external_product_id"])
        assertEquals("GPA.501", metadata["external_order_id"])
        assertEquals("SUBSCRIPTION_STATE_ACTIVE", metadata["external_status"])
        assertEquals("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED", metadata["acknowledgement_state"])

        val due = repository.listPlayStoreSubscriptionsDueForVerification(
            staleBefore = now.minusSeconds(60),
            renewalWindowEnd = Instant.parse("2026-06-07T00:00:00Z"),
            limit = 10
        )
        assertEquals(1, due.size)
        assertEquals(user, due.single().userId)
        assertEquals("pynapli_select_monthly", due.single().productId)
        assertEquals(SubscriptionTier.SELECT, due.single().tier)
        assertEquals(purchaseToken, due.single().purchaseToken)
    }

    private fun DataSource.insertUser(userId: UUID) {
        connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO users (id, status)
                VALUES (?, 'active')
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
        }
    }

    private fun DataSource.insertActiveProfileLink(userId: UUID, linkedUserId: UUID) {
        connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO user_profile_links (
                    id,
                    user_id,
                    linked_user_id,
                    status,
                    accepted_at
                )
                VALUES (?, ?, ?, 'active', now())
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setObject(3, linkedUserId)
                statement.executeUpdate()
            }
        }
    }

    private fun DataSource.insertSubscription(
        userId: UUID,
        tier: String,
        externalId: String,
        status: String = "active",
        startsAt: Instant,
        endsAt: Instant
    ) {
        connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO subscriptions (
                    id,
                    user_id,
                    platform,
                    external_subscription_id,
                    tier,
                    status,
                    current_period_start,
                    current_period_end
                )
                VALUES (?, ?, 'dev', ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setString(3, externalId)
                statement.setString(4, tier)
                statement.setString(5, status)
                statement.setTimestamp(6, Timestamp.from(startsAt))
                statement.setTimestamp(7, Timestamp.from(endsAt))
                statement.executeUpdate()
            }
        }
    }

    private fun DataSource.insertEntitlement(
        userId: UUID,
        tier: String,
        source: String,
        status: String = "active",
        sourceId: UUID? = null,
        startsAt: Instant,
        endsAt: Instant?
    ) {
        connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO entitlements (
                    id,
                    user_id,
                    tier,
                    source,
                    source_id,
                    starts_at,
                    ends_at,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setString(3, tier)
                statement.setString(4, source)
                if (sourceId == null) {
                    statement.setNull(5, Types.OTHER)
                } else {
                    statement.setObject(5, sourceId)
                }
                statement.setTimestamp(6, Timestamp.from(startsAt))
                if (endsAt == null) {
                    statement.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE)
                } else {
                    statement.setTimestamp(7, Timestamp.from(endsAt))
                }
                statement.setString(8, status)
                statement.executeUpdate()
            }
        }
    }

    private fun DataSource.insertPremiumInventory(
        userId: UUID,
        inventoryType: String,
        quantity: Int,
        expiresAt: Instant?
    ) {
        connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO premium_inventory (
                    id,
                    user_id,
                    inventory_type,
                    quantity,
                    source,
                    expires_at
                )
                VALUES (?, ?, ?, ?, 'dev', ?)
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setString(3, inventoryType)
                statement.setInt(4, quantity)
                if (expiresAt == null) {
                    statement.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE)
                } else {
                    statement.setTimestamp(5, Timestamp.from(expiresAt))
                }
                statement.executeUpdate()
            }
        }
    }

    private fun DataSource.inventoryQuantity(userId: UUID, inventoryType: String): Int =
        connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT COALESCE(sum(quantity), 0)::integer
                FROM premium_inventory
                WHERE user_id = ?
                  AND inventory_type = ?
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, inventoryType)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun DataSource.tableExists(tableName: String): Boolean =
        connection.use { connection ->
            connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
                statement.setString(1, tableName)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
        }

    private fun DataSource.indexExists(indexName: String): Boolean =
        connection.use { connection ->
            connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
                statement.setString(1, indexName)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
        }

    private fun DataSource.columnExists(tableName: String, columnName: String): Boolean =
        connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_name = ?
                      AND column_name = ?
                )
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, tableName)
                statement.setString(2, columnName)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
        }

    private fun DataSource.subscriptionMetadata(purchaseToken: String): Map<String, String?> =
        connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT external_product_id,
                       external_order_id,
                       external_status,
                       acknowledgement_state
                FROM subscriptions
                WHERE platform = 'play_store'
                  AND external_subscription_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, purchaseToken)
                statement.executeQuery().use { rs ->
                    rs.next()
                    mapOf(
                        "external_product_id" to rs.getString("external_product_id"),
                        "external_order_id" to rs.getString("external_order_id"),
                        "external_status" to rs.getString("external_status"),
                        "acknowledgement_state" to rs.getString("acknowledgement_state")
                    )
                }
            }
        }

    companion object {
        private val postgisImage = DockerImageName
            .parse("postgis/postgis:16-3.4")
            .asCompatibleSubstituteFor("postgres")

        private val postgres = PostgreSQLContainer<Nothing>(postgisImage).apply {
            withDatabaseName("pynapli_subscription_test")
            withUsername("pynapli")
            withPassword("pynapli_test_password")
        }

        @JvmStatic
        @BeforeClass
        fun startPostgres() {
            postgres.start()
        }

        @JvmStatic
        @AfterClass
        fun stopPostgres() {
            postgres.stop()
        }
    }
}

private fun uuid(value: Int): UUID =
    UUID.fromString("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
