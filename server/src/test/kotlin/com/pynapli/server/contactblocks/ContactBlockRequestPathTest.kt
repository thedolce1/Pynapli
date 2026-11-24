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
 * Unit half of the request-path rules (the statements actually executed are recorded against the
 * database in ContactBlockRequestPathIntegrationTest): the service makes the same calls for any valid
 * address and only nudges the resolver. Plus the keyed tokens, and the production wiring.
 *
 * The wiring check reads the DI files: the codebase has no test that builds the real
 * ServerDependencies graph (it needs Redis, Firebase and the full config), so a builder that forgot to
 * pass the rule is caught here, formatting-proof, rather than by constructing the graph.
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
            releasedAt: Instant,
        ) {
            calls += "remove"
        }

        override suspend fun removeAll(
            blockerUserId: UUID,
            releasedAt: Instant,
        ) {
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
        val server = root.resolve("ServerDependencies.kt").readText().compact()
        val core = root.resolve("CoreDependencies.kt").readText().compact()

        listOf(
            "JdbcActivityRepository(database.dataSource, contactBlockTokens = core.contactBlockTokens)",
            "contactBlockTokens = core.contactBlockTokens,",
            "onAccountVerified = core.contactBlockSettling::atVerify",
            "contactBlockVisibility = core.contactBlockVisibility,",
            "buildMetInPersonDependencies(config, database.dataSource, core.contactBlockVisibility)",
        ).forEach { wiring -> assertTrue(wiring.compact() in server, "ServerDependencies must pass: $wiring") }
        // Activity, favorites, profile, profile links, media, grid, tonight, messaging, swipe, the field.
        assertTrue(
            Regex("""contactBlockVisibility=core\.contactBlockVisibility""").findAll(server).count() >= 10,
            "every service builder in ServerDependencies must get the viewer-side rule",
        )
        listOf(
            "onEmailSignIn = contactBlockSettling::atSignIn",
            "NotificationService(notificationRepository, contactBlockVisibility = contactBlockVisibility)",
            "JdbcAccountLifecycleRepository(database.dataSource, contactBlockTokens, metInPersonAccountData = metInPersonAccountData(config))",
            "JdbcAccountDataExportArchiver(database.dataSource, contactBlockTokens = contactBlockTokens, metInPersonAccountData = metInPersonAccountData(config))",
        ).forEach { wiring -> assertTrue(wiring.compact() in core, "CoreDependencies must pass: $wiring") }
        listOf("GridDependencies.kt", "SwipeDependencies.kt", "TonightDependencies.kt", "MessagingDependencies.kt", "MediaDependencies.kt").forEach { file ->
            assertTrue("contactBlockVisibility=contactBlockVisibility" in root.resolve(file).readText().compact(), "$file must forward the rule")
        }
        assertTrue("JdbcMessagingRepository(dataSource,contactBlockTokens=contactBlockTokens)" in root.resolve("MessagingDependencies.kt").readText().compact())
        // Swipe forwards it to the match moment too.
        assertTrue(
            Regex("""contactBlockVisibility=contactBlockVisibility""").findAll(root.resolve("SwipeDependencies.kt").readText().compact()).count() >= 3,
            "SwipeDependencies must forward the rule to Swipe, Psync and the match moment",
        )
    }

    /** Formatting-proof comparison: no whitespace, no trailing comma before a closing parenthesis. */
    private fun String.compact(): String = replace(Regex("""\s+"""), "").replace(",)", ")").trimEnd(',')

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
