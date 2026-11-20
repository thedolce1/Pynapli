package com.pynapli.server.contactblocks

import com.pynapli.server.safety.SafetyException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The blocker's own requests can't depend on membership: they never read `users`, never resolve, and
 * do the same calls for any valid address. Plus the keyed tokens, and a scan that production wiring
 * hands the rule to every surface (the integration tests build their own graph, so they can't see a
 * builder that forgot it).
 */
class ContactBlockRequestPathTest {
    private class RecordingRepository : ContactBlockRepository {
        val calls = mutableListOf<String>()

        override suspend fun add(
            id: UUID,
            blockerUserId: UUID,
            emailToken: String,
            createdAt: Instant,
            maxRows: Int,
        ): ContactBlockAddOutcome {
            calls += "add($maxRows)"
            return ContactBlockAddOutcome.ADDED
        }

        override suspend fun remove(
            blockerUserId: UUID,
            emailToken: String,
        ) {
            calls += "remove"
        }

        override suspend fun removeAll(blockerUserId: UUID) {
            calls += "removeAll"
        }

        override suspend fun count(blockerUserId: UUID): Int {
            calls += "count"
            return 0
        }
    }

    private val blocker = UUID.fromString("00000000-0000-0000-0000-00000000c2a1")
    private val tokens = ContactBlockTokens.localDevelopment()

    @Test
    fun anAddDoesTheSameCallsForAnyAddressAndOnlyNudgesTheResolver() =
        runBlocking<Unit> {
            val repository = RecordingRepository()
            val trigger = CountingResolutionTrigger()
            val service = ContactBlockService(repository, tokens, trigger)

            service.add(blocker, "member@example.com")
            val firstCalls = repository.calls.toList()
            service.add(blocker, "nobody-at-all@example.com")

            assertEquals(listOf("add(${ContactBlockService.MAX_ADDRESSES})"), firstCalls)
            assertEquals(firstCalls + firstCalls, repository.calls)
            assertEquals(2, trigger.nudges.get(), "the add only nudges; it never resolves")
        }

    @Test
    fun removeAndRemoveAllAnswerTheSameWhetherOrNotAnythingWasAdded() =
        runBlocking<Unit> {
            val repository = RecordingRepository()
            val service = ContactBlockService(repository, tokens, CountingResolutionTrigger())

            service.remove(blocker, "never-added@example.com")
            service.removeAll(blocker)

            assertEquals(listOf("remove", "removeAll"), repository.calls)
        }

    @Test
    fun aMalformedAddressIsRefusedBeforeAnyStorage() =
        runBlocking<Unit> {
            val repository = RecordingRepository()
            val trigger = CountingResolutionTrigger()
            val service = ContactBlockService(repository, tokens, trigger)

            listOf("not an address", "a@b", "two@@example.com", "a@example.com, b@example.com").forEach { address ->
                assertEquals("INVALID_EMAIL", assertFailsWith<SafetyException> { service.add(blocker, address) }.code)
            }
            assertTrue(repository.calls.isEmpty())
            assertEquals(0, trigger.nudges.get())
        }

    @Test
    fun theRequestPathSqlNeverTouchesUsers() {
        JdbcContactBlockRepository.REQUEST_PATH_SQL.forEach { sql ->
            assertTrue(!Regex("""\busers\b""").containsMatchIn(sql), "request-path SQL reads users: $sql")
        }
    }

    @Test
    fun tokensAreKeyedNormalisedAndUnlinkable() {
        val other = ContactBlockTokens("another-email-key-for-this-test-only", "another-target-key-for-this-test-only")
        val address = "boss@example.com"
        val member = UUID.fromString("00000000-0000-0000-0000-00000000c2a2")

        assertEquals(tokens.emailToken(address), tokens.emailToken(address))
        assertNotEquals(tokens.emailToken(address), other.emailToken(address), "a different key gives a different code")
        // users.contact_token is never the row's own code, so the tables can't be joined without K2.
        assertNotEquals(tokens.emailToken(address), tokens.memberToken(tokens.emailToken(address)))
        assertNotEquals(tokens.targetToken(member), tokens.exemptToken(member))
        assertEquals(43, tokens.targetToken(member).length, "SHA-256, base64url without padding")
    }

    @Test
    fun productionWiringHandsTheRuleToEverySurface() {
        val root = repositoryRoot().resolve("server/src/main/kotlin/com/pynapli/server/dependencies")
        val server = root.resolve("ServerDependencies.kt").readText()
        val core = root.resolve("CoreDependencies.kt").readText()

        listOf(
            "JdbcActivityRepository(database.dataSource, contactBlockTokens = core.contactBlockTokens)",
            "contactBlockTokens = core.contactBlockTokens,",
            "onAccountVerified = core.contactBlockResolver::bindNewlyVerifiedAccount",
            "contactBlockVisibility = core.contactBlockVisibility,",
        ).forEach { wiring -> assertTrue(wiring in server, "ServerDependencies must pass: $wiring") }
        // Activity, favorites, profile, profile links, media, grid, tonight, messaging, swipe, the field.
        assertTrue(
            Regex("""contactBlockVisibility = core\.contactBlockVisibility""").findAll(server).count() >= 10,
            "every service builder in ServerDependencies must get the viewer-side rule",
        )
        listOf(
            "onEmailSignIn = contactBlockResolver::recordAddressAtSignIn",
            "NotificationService(notificationRepository, contactBlockVisibility = contactBlockVisibility)",
            "JdbcAccountLifecycleRepository(database.dataSource, contactBlockTokens)",
            "JdbcAccountDataExportArchiver(database.dataSource, contactBlockTokens = contactBlockTokens)",
        ).forEach { wiring -> assertTrue(wiring in core, "CoreDependencies must pass: $wiring") }
        listOf("GridDependencies.kt", "SwipeDependencies.kt", "TonightDependencies.kt", "MessagingDependencies.kt", "MediaDependencies.kt").forEach { file ->
            assertTrue("contactBlockVisibility = contactBlockVisibility" in root.resolve(file).readText(), "$file must forward the rule")
        }
        assertTrue("JdbcMessagingRepository(dataSource, contactBlockTokens = contactBlockTokens)" in root.resolve("MessagingDependencies.kt").readText())
    }

    private fun repositoryRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("settings.gradle.kts")) && Files.exists(current.resolve("docs"))) {
                return current
            }
            current = current.parent ?: current
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}.")
    }
}
