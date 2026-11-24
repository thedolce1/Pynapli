package com.pynapli.server.metinperson

import com.pynapli.server.account.JdbcAccountDataExportArchiver
import com.pynapli.server.account.JdbcAccountLifecycleRepository
import com.pynapli.server.contactblocks.ContactBlockTokens
import com.pynapli.server.messaging.JdbcMessagingRepository
import com.pynapli.server.messaging.MessagingException
import com.pynapli.server.messaging.MessagingService
import com.pynapli.server.profile.JdbcProfileRepository
import com.pynapli.server.profile.toDto
import com.pynapli.server.profilelink.JdbcProfileLinkRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.raceTogether
import com.pynapli.shared.network.dto.ConfirmMetInPersonRequestDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.MetInPersonOfferDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.WithdrawMetInPersonRequestDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Met in person" end to end against the real schema (TRUST_AND_SAFETY_SPEC "Met in person"): who can
 * confirm whom, what the voucher sees, that nothing observable changes between batches or at the batch
 * that first sees a crossing, the hysteresis, the exclusions and who controls them, the couple rule,
 * the storage a database copy shows without the key, deletion and export.
 */
class MetInPersonIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val clock = MetInPersonTestClock(Instant.parse("2026-11-18T12:00:00Z"))
    private val tokens = MetInPersonTokens("met-in-person-integration-key-0123456789")
    private val fx = MetInPersonFixtures(dataSource, clock)
    private val service = MetInPersonService(dataSource, tokens, clock)
    private val batch = MetInPersonBadgeBatch(dataSource, tokens, clock, Random(42), chunkSize = 3)
    private val profiles = JdbcProfileRepository(dataSource)
    private val messaging = MessagingService(JdbcMessagingRepository(dataSource, ContactBlockTokens.localDevelopment()))
    private val json = Json { encodeDefaults = true }

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private suspend fun confirm(
        voucher: UUID,
        conversation: UUID,
        subject: UUID,
        includePartner: Boolean = false,
    ): MetInPersonOfferDto = service.confirm(voucher, conversation.toString(), ConfirmMetInPersonRequestDto(subject.toString(), includePartner))

    private suspend fun withdraw(
        voucher: UUID,
        conversation: UUID,
        subject: UUID,
    ): MetInPersonOfferDto = service.withdraw(voucher, conversation.toString(), WithdrawMetInPersonRequestDto(subject.toString()))

    private suspend fun offer(
        viewer: UUID,
        conversation: UUID,
        subject: UUID,
    ): MetInPersonOfferDto? = service.offers(viewer, conversation.toString()).offers.firstOrNull { it.subjectUserId == subject.toString() }

    private suspend fun publicProfile(
        viewer: UUID,
        subject: UUID,
    ): PublicProfileDto = assertNotNull(profiles.getPublicProfile(viewer, subject)).toDto(null)

    /** Enough batches for any crossing to take effect (on: 1 + up to 4; off: 2 + up to 4). */
    private suspend fun settle() {
        repeat(8) { batch.recomputeNow() }
    }

    private suspend fun assertRefused(
        viewer: UUID,
        conversation: UUID,
        subject: UUID,
    ) {
        assertNull(offer(viewer, conversation, subject)?.takeIf { it.canConfirm })
        val refusal = assertFailsWith<MessagingException> { confirm(viewer, conversation, subject) }
        assertEquals(MetInPersonService.NOT_AVAILABLE, refusal.code)
    }

    /** [count] verified members who each talked with [subject] and confirm them. Returns (voucher, chat). */
    private suspend fun confirmedBy(
        subject: UUID,
        count: Int,
    ): List<Pair<UUID, UUID>> =
        (1..count).map { index ->
            val voucher = fx.member("Voucher $index")
            val conversation = fx.talked(voucher, subject)
            confirm(voucher, conversation, subject)
            voucher to conversation
        }

    @Test
    fun aVerifiedMemberWhoTalkedBothWaysIsOffered() {
        runBlocking {
            val subject = fx.member("Sam")
            val voucher = fx.member("Alex")
            val state = assertNotNull(offer(voucher, fx.talked(voucher, subject), subject))
            assertTrue(state.canConfirm)
            assertEquals("Sam", state.subjectDisplayName)
        }
    }

    @Test
    fun eachWriteTimeRuleRefusesWithTheSameCode() {
        runBlocking {
            val subject = fx.member("Sam")
            fx.member("Unverified", verified = false).let { assertRefused(it, fx.talked(it, subject), subject) }
            fx.member("Young", accountAge = Duration.ofDays(29)).let { assertRefused(it, fx.talked(it, subject), subject) }
            val unverifiedSubject = fx.member("Unverified subject", verified = false)
            fx.member("V1").let { assertRefused(it, fx.talked(it, unverifiedSubject), unverifiedSubject) }
            fx.member("Monologue").let { voucher ->
                val conversation = fx.conversation(voucher, subject)
                fx.message(conversation, voucher, Duration.ofDays(5))
                assertRefused(voucher, conversation, subject)
            }
            fx.member("Held").let { voucher ->
                val conversation = fx.conversation(voucher, subject)
                fx.message(conversation, voucher, Duration.ofDays(5))
                fx.message(conversation, subject, Duration.ofDays(4), moderationStatus = "held")
                assertRefused(voucher, conversation, subject)
            }
            fx.member("Hasty").let { voucher ->
                val conversation = fx.conversation(voucher, subject)
                fx.message(conversation, voucher, Duration.ofHours(23))
                fx.message(conversation, subject, Duration.ofHours(22))
                assertRefused(voucher, conversation, subject)
            }
            fx.member("Stale").let { voucher ->
                val conversation = fx.conversation(voucher, subject)
                fx.message(conversation, voucher, Duration.ofDays(120))
                fx.message(conversation, subject, Duration.ofDays(91))
                assertRefused(voucher, conversation, subject)
            }
            fx.member("Partner").let { voucher ->
                fx.link(voucher, subject)
                assertRefused(voucher, fx.talked(voucher, subject), subject)
            }
            fx.member("Ex").let { voucher ->
                fx.link(voucher, subject, status = "unlinked")
                assertRefused(voucher, fx.talked(voucher, subject), subject)
            }
            fx.member("Blocker").let { voucher ->
                val conversation = fx.talked(voucher, subject)
                fx.block(voucher, subject)
                assertRefused(voucher, conversation, subject)
            }
            assertEquals(0, fx.vouchRows())
        }
    }

    @Test
    fun theOfferNeverRevealsTheViewersOwnRiskTier() {
        runBlocking {
            val subject = fx.member("Sam")
            listOf("watch", "friction", "quarantine").forEach { tier ->
                val voucher = fx.member("Tier $tier", riskTier = tier)
                assertTrue(assertNotNull(offer(voucher, fx.talked(voucher, subject), subject)).canConfirm, tier)
            }
        }
    }

    @Test
    fun inAGroupOnlyMessagesAfterBothJoinedCount() {
        runBlocking {
            val subject = fx.member("Sam")
            val other = fx.member("Other")
            val late = fx.member("Late joiner")
            val group = fx.conversation(subject, other)
            fx.message(group, subject, Duration.ofDays(15))
            fx.join(group, late, Duration.ofDays(10))
            fx.message(group, late, Duration.ofDays(9))
            assertRefused(late, group, subject)
            fx.message(group, subject, Duration.ofDays(8))
            assertTrue(assertNotNull(offer(late, group, subject)).canConfirm)
        }
    }

    @Test
    fun aPersonBadgeNeedsThreeAndAppearsOnlyAfterTheDelay() {
        runBlocking {
            val subject = fx.member("Sam")
            val stranger = fx.member("Stranger")
            fx.optIn(subject)
            confirmedBy(subject, 2)
            settle()
            assertFalse(publicProfile(stranger, subject).metInPerson)

            val third = fx.member("Third")
            confirm(third, fx.talked(third, subject), subject)
            assertFalse(publicProfile(stranger, subject).metInPerson, "a confirmation never changes the badge by itself")
            batch.recomputeNow()
            assertFalse(publicProfile(stranger, subject).metInPerson, "the batch that first sees the crossing never shows it")
            settle()
            assertTrue(publicProfile(stranger, subject).metInPerson)
        }
    }

    @Test
    fun nothingObservableDiffersBetweenAConfirmThatCompletesTheThresholdAndOneThatDoesNot() {
        runBlocking {
            val subject = fx.member("Sam")
            val stranger = fx.member("Stranger")
            fx.optIn(subject)
            val archiver = JdbcAccountDataExportArchiver(dataSource, clock, ContactBlockTokens.localDevelopment(), MetInPersonAccountData(tokens))
            confirmedBy(subject, 2)
            settle()

            fun Json.metInPersonPart(text: String) = parseToJsonElement(text).jsonObject["metInPerson"].toString()

            val publicBefore = json.encodeToString(PublicProfileDto.serializer(), publicProfile(stranger, subject))
            val meBefore = json.encodeToString(MeDto.serializer(), assertNotNull(profiles.getMe(subject)).toDto())
            val exportBefore = json.metInPersonPart(archiver.buildArchive(subject).bytes.decodeToString())

            val notCompleting = fx.member("Second subject's voucher")
            val otherSubject = fx.member("Other subject")
            val notCompletingAnswer = confirm(notCompleting, fx.talked(notCompleting, otherSubject), otherSubject)
            val completing = fx.member("Third")
            val completingAnswer = confirm(completing, fx.talked(completing, subject), subject)
            assertEquals(notCompletingAnswer.copy(subjectUserId = "", subjectDisplayName = ""), completingAnswer.copy(subjectUserId = "", subjectDisplayName = ""))

            batch.recomputeNow()
            assertEquals(publicBefore, json.encodeToString(PublicProfileDto.serializer(), publicProfile(stranger, subject)))
            assertEquals(meBefore, json.encodeToString(MeDto.serializer(), assertNotNull(profiles.getMe(subject)).toDto()))
            assertEquals(exportBefore, json.metInPersonPart(archiver.buildArchive(subject).bytes.decodeToString()))
            assertEquals(
                json.metInPersonPart(archiver.buildArchive(notCompleting).bytes.decodeToString()).length - "Other subject".length,
                json.metInPersonPart(archiver.buildArchive(completing).bytes.decodeToString()).length - "Sam".length,
                "the two vouchers' exports have the same shape",
            )
        }
    }

    @Test
    fun betweenBatchesNoSubjectFacingOrPublicResponseChanges() {
        runBlocking {
            val subject = fx.member("Sam")
            val stranger = fx.member("Stranger")
            fx.optIn(subject)
            val friend = fx.member("Friend")
            val friendConversation = fx.talked(friend, subject)
            settle()
            val publicBefore = json.encodeToString(PublicProfileDto.serializer(), publicProfile(stranger, subject))
            val meBefore = json.encodeToString(MeDto.serializer(), assertNotNull(profiles.getMe(subject)).toDto())
            val readBefore = readMarkJson(subject, friendConversation)
            val subjectsOwnOfferBefore = offer(subject, friendConversation, friend)

            confirm(friend, friendConversation, subject)
            val vouchers = confirmedBy(subject, 5).map { it.first }

            assertEquals(publicBefore, json.encodeToString(PublicProfileDto.serializer(), publicProfile(stranger, subject)))
            assertEquals(meBefore, json.encodeToString(MeDto.serializer(), assertNotNull(profiles.getMe(subject)).toDto()))
            assertEquals(readBefore, readMarkJson(subject, friendConversation))
            assertEquals(subjectsOwnOfferBefore, offer(subject, friendConversation, friend))

            val publicText = json.encodeToString(PublicProfileDto.serializer(), publicProfile(stranger, subject))
            (vouchers + friend).forEach { assertFalse(publicText.contains(it.toString())) }
            listOf(publicText, meBefore, readBefore).forEach { text ->
                assertFalse(Regex("\"[A-Za-z]*([Vv]ouch|MetInPerson[A-Za-z]*Count|metInPersonCount)[A-Za-z]*\"\\s*:").containsMatchIn(text), text)
            }
            assertFalse(readBefore.contains("metInPerson"), "the read mark carries nothing about it")
            val keys = Json.parseToJsonElement(publicText).jsonObject.keys.filter { it.startsWith("metInPerson") }
            assertEquals(setOf("metInPerson", "metInPersonAsCouple"), keys.toSet())
        }
    }

    private suspend fun readMarkJson(
        viewer: UUID,
        conversation: UUID,
    ): String = json.encodeToString(ConversationDto.serializer(), messaging.markConversationRead(viewer, conversation.toString()))

    @Test
    fun theOptInGatesTheBadgeAndAQuarantinedSubjectShowsNone() {
        runBlocking {
            val subject = fx.member("Sam")
            val stranger = fx.member("Stranger")
            confirmedBy(subject, 3)
            settle()
            assertFalse(publicProfile(stranger, subject).metInPerson, "off by default")
            fx.optIn(subject)
            assertTrue(publicProfile(stranger, subject).metInPerson)

            fx.setRiskTier(subject, "quarantine")
            assertFalse(publicProfile(subject, subject).metInPerson, "not even on their own preview")
            fx.setRiskTier(subject, "clean")
            fx.setRiskTier(subject, "terminate")
            settle()
            fx.setRiskTier(subject, "clean")
            assertFalse(publicProfile(stranger, subject).metInPerson, "the batch turned it off")
        }
    }

    @Test
    fun nothingTheSubjectDoesChangesWhatCounts() {
        runBlocking {
            val subject = fx.member("Sam")
            val stranger = fx.member("Stranger")
            fx.optIn(subject)
            val vouchers = confirmedBy(subject, 3)
            settle()
            assertTrue(publicProfile(stranger, subject).metInPerson)

            val (target, targetConversation) = vouchers[0]
            fx.block(subject, target)
            settle()
            assertTrue(publicProfile(stranger, subject).metInPerson, "blocking a voucher")
            dataSource.connection.use { it.createStatement().use { s -> s.executeUpdate("DELETE FROM user_blocks") } }
            confirm(subject, targetConversation, target)
            fx.hide(subject, target)
            fx.inviteToLink(subject, target)
            fx.setRiskTier(target, "friction")
            settle()
            assertTrue(publicProfile(stranger, subject).metInPerson, "hiding, inviting to link, reporting into friction or confirming back")
        }
    }

    @Test
    fun theVouchersOwnActsAndTheServiceDecide() {
        runBlocking {
            val subject = fx.member("Sam")
            val stranger = fx.member("Stranger")
            fx.optIn(subject)
            val vouchers = confirmedBy(subject, 3).map { it.first }
            settle()
            assertTrue(publicProfile(stranger, subject).metInPerson)

            fx.block(vouchers[0], subject)
            settle()
            assertFalse(publicProfile(stranger, subject).metInPerson, "the voucher's own block")
            dataSource.connection.use { it.createStatement().use { s -> s.executeUpdate("DELETE FROM user_blocks") } }
            settle()
            assertTrue(publicProfile(stranger, subject).metInPerson)

            fx.setRiskTier(vouchers[1], "quarantine")
            settle()
            assertFalse(publicProfile(stranger, subject).metInPerson, "a quarantined voucher")
        }
    }

    @Test
    fun linksAndDevicesCountOnlyWhenTheyPredateTheConfirmation() {
        runBlocking {
            val subject = fx.member("Sam")
            val stranger = fx.member("Stranger")
            fx.optIn(subject)
            val vouchers = confirmedBy(subject, 3).map { it.first }
            // Both happen after the confirmations: they change nothing.
            fx.shareDevice(vouchers[0], subject, ago = Duration.ofDays(-1))
            fx.link(vouchers[1], subject)
            dataSource.connection.use { connection ->
                connection.prepareStatement("UPDATE user_profile_links SET accepted_at = ?").use {
                    it.setTimestamp(1, Timestamp.from(clock.instant().plus(Duration.ofDays(2))))
                    it.executeUpdate()
                }
            }
            settle()
            assertTrue(publicProfile(stranger, subject).metInPerson)

            // A device both used before the day given: never counts.
            val sameDevice = fx.member("Same device")
            fx.shareDevice(sameDevice, subject)
            val fourth = fx.member("Fourth")
            confirm(sameDevice, fx.talked(sameDevice, subject), subject)
            confirm(fourth, fx.talked(fourth, subject), subject)
            val evaluation = MetInPersonEvaluator(tokens).let { evaluator -> dataSource.connection.use { evaluator.evaluate(it, listOf(subject), clock.instant()) } }
            assertEquals(4, evaluation.getValue(subject).countedVouchers.size)
            assertFalse(sameDevice in evaluation.getValue(subject).countedVouchers)
        }
    }

    @Test
    fun linkedOrDeviceSharingVouchersCountOnce() {
        runBlocking {
            val subject = fx.member("Sam")
            val stranger = fx.member("Stranger")
            fx.optIn(subject)
            val vouchers = confirmedBy(subject, 4).map { it.first }
            fx.link(vouchers[0], vouchers[1])
            fx.shareDevice(vouchers[2], vouchers[3])
            settle()
            assertFalse(publicProfile(stranger, subject).metInPerson, "four accounts, two independent vouchers")
            val fifth = fx.member("Fifth")
            confirm(fifth, fx.talked(fifth, subject), subject)
            settle()
            assertTrue(publicProfile(stranger, subject).metInPerson)
        }
    }

    @Test
    fun aSameDayCliqueOfFourCannotBadgeItself() {
        runBlocking {
            val stranger = fx.member("Stranger")
            val clique = (1..4).map { fx.member("Friend $it") }
            clique.forEach { fx.optIn(it) }
            val chats = mutableMapOf<Pair<UUID, UUID>, UUID>()
            clique.forEach { a -> clique.forEach { b -> if (a.toString() < b.toString()) chats[a to b] = fx.talked(a, b) } }
            fun chat(
                a: UUID,
                b: UUID,
            ) = chats[a to b] ?: chats.getValue(b to a)
            clique.forEach { a -> clique.forEach { b -> if (a != b) confirm(a, chat(a, b), b) } }
            settle()
            clique.forEach { assertFalse(publicProfile(stranger, it).metInPerson) }

            val outsider = fx.member("Outsider")
            confirm(outsider, fx.talked(outsider, clique[0]), clique[0])
            settle()
            assertTrue(publicProfile(stranger, clique[0]).metInPerson, "one voucher the subject had not confirmed first")
        }
    }

    @Test
    fun takingBackBlocksGivingAgainFor180DaysAndKeepsTheSlot() {
        runBlocking {
            val subject = fx.member("Sam")
            val stranger = fx.member("Stranger")
            fx.optIn(subject)
            val vouchers = confirmedBy(subject, 3)
            settle()
            assertTrue(publicProfile(stranger, subject).metInPerson)

            val (toggler, chat) = vouchers[0]
            assertFalse(withdraw(toggler, chat, subject).given)
            batch.recomputeNow()
            assertTrue(publicProfile(stranger, subject).metInPerson, "one batch below does not turn it off")
            assertRefused(toggler, chat, subject)
            clock.advance(Duration.ofDays(7))
            fx.message(chat, toggler, Duration.ofDays(1))
            assertRefused(toggler, chat, subject)

            clock.advance(Duration.ofDays(174))
            fx.message(chat, toggler, Duration.ofDays(1))
            fx.message(chat, subject, Duration.ofDays(1))
            assertTrue(confirm(toggler, chat, subject).given, "after 180 days it can be given again")
            assertEquals(1, fx.count("SELECT count(*) FROM vouches WHERE pair_token = ?", tokens.pair(toggler, subject)))
        }
    }

    @Test
    fun aWithdrawnConfirmationStillUsesItsSlotAndConcurrentConfirmsCannotPassTheCap() {
        runBlocking {
            val voucher = fx.member("Busy")
            val chats =
                (1..MetInPersonRules.MONTHLY_CAP - 1).map { index ->
                    val subject = fx.member("S$index")
                    val chat = fx.talked(voucher, subject)
                    confirm(voucher, chat, subject)
                    subject to chat
                }
            withdraw(voucher, chats[0].second, chats[0].first)
            val a = fx.member("A")
            val b = fx.member("B")
            val chatA = fx.talked(voucher, a)
            val chatB = fx.talked(voucher, b)
            val (first, second) = raceTogether({ confirm(voucher, chatA, a) }, { confirm(voucher, chatB, b) })
            assertEquals(1, listOf(first, second).count { it.isSuccess }, "nine given (one withdrawn) leaves one slot")
        }
    }

    @Test
    fun confirmationsExpireAfterTwentyFourMonths() {
        runBlocking {
            val subject = fx.member("Sam")
            val stranger = fx.member("Stranger")
            fx.optIn(subject)
            confirmedBy(subject, 3)
            settle()
            assertTrue(publicProfile(stranger, subject).metInPerson)
            clock.advance(Duration.ofDays(731))
            settle()
            assertFalse(publicProfile(stranger, subject).metInPerson)
        }
    }

    @Test
    fun aVoucherCanTakeItBackAfterLeavingTheConversation() {
        runBlocking {
            val subject = fx.member("Sam")
            val voucher = fx.member("Alex")
            val chat = fx.talked(voucher, subject)
            confirm(voucher, chat, subject)
            fx.leave(chat, voucher)
            assertFalse(withdraw(voucher, chat, subject).given)
        }
    }

    /** Sam and Riley are linked; each voucher talks with both in one group chat and ticks Riley. */
    private suspend fun coupleConfirmedBy(
        sam: UUID,
        riley: UUID,
        count: Int,
    ) {
        repeat(count) { index ->
            val voucher = fx.member("Group voucher $index")
            val group = fx.conversation(sam, riley, voucher)
            fx.message(group, voucher, Duration.ofDays(10))
            fx.message(group, sam, Duration.ofDays(9))
            fx.message(group, riley, Duration.ofDays(8))
            assertEquals(riley.toString(), assertNotNull(offer(voucher, group, sam)).partner?.partnerUserId)
            confirm(voucher, group, sam, includePartner = true)
            assertTrue(assertNotNull(offer(voucher, group, riley)).given)
        }
    }

    @Test
    fun aCoupleBadgeNeedsThreeIndependentVouchersWhoMetBothAndBothOptIns() {
        runBlocking {
            val sam = fx.member("Sam")
            val riley = fx.member("Riley")
            val stranger = fx.member("Stranger")
            fx.link(sam, riley)
            fx.optIn(sam)
            coupleConfirmedBy(sam, riley, 3)
            settle()
            assertTrue(publicProfile(stranger, sam).metInPerson)
            assertFalse(publicProfile(stranger, sam).metInPersonAsCouple, "Riley has not turned it on")
            fx.optIn(riley)
            assertTrue(publicProfile(stranger, sam).metInPersonAsCouple)
            assertTrue(publicProfile(stranger, riley).metInPersonAsCouple)
        }
    }

    @Test
    fun thePartnerTickNeverNamesAPartnerTheProfileWouldHide() {
        runBlocking {
            val sam = fx.member("Sam")
            val riley = fx.member("Riley")
            fx.link(sam, riley)
            val voucher = fx.member("Voucher")
            val group = fx.conversation(sam, riley, voucher)
            fx.message(group, voucher, Duration.ofDays(10))
            fx.message(group, sam, Duration.ofDays(9))
            fx.message(group, riley, Duration.ofDays(8))
            assertNotNull(assertNotNull(offer(voucher, group, sam)).partner)

            fx.setDiscovery(sam, "partner_visibility", "hidden")
            assertNull(assertNotNull(offer(voucher, group, sam)).partner, "Sam hides the link")
            fx.setDiscovery(sam, "partner_visibility", "public")
            fx.setDiscovery(riley, "incognito", true)
            assertNull(assertNotNull(offer(voucher, group, sam)).partner, "Riley is incognito")
            fx.setDiscovery(riley, "incognito", false)
            fx.hide(voucher, riley)
            assertNull(assertNotNull(offer(voucher, group, sam)).partner, "the viewer hid Riley")
        }
    }

    @Test
    fun unlinkingVoidsOnlyTheCoupleBadgeAndARelinkDoesNotReviveEvenUnderAConcurrentBatch() {
        runBlocking {
            val sam = fx.member("Sam")
            val riley = fx.member("Riley")
            val stranger = fx.member("Stranger")
            fx.link(sam, riley)
            fx.optIn(sam)
            fx.optIn(riley)
            coupleConfirmedBy(sam, riley, 3)
            settle()
            assertTrue(publicProfile(stranger, sam).metInPersonAsCouple)

            val (_, unlinked) = raceTogether({ batch.recomputeNow() }, { JdbcProfileLinkRepository(dataSource).unlinkCurrent(sam) })
            assertTrue(unlinked.getOrThrow())
            assertFalse(publicProfile(stranger, sam).metInPersonAsCouple)
            assertTrue(publicProfile(stranger, sam).metInPerson)

            dataSource.connection.use { connection ->
                connection.createStatement().use {
                    it.executeUpdate("UPDATE user_profile_links SET status = 'active', unlinked_at = NULL, updated_at = now()")
                }
            }
            assertFalse(publicProfile(stranger, sam).metInPersonAsCouple, "right after the relink")
            settle()
            assertFalse(publicProfile(stranger, sam).metInPersonAsCouple, "old couple confirmations stay void")
            assertTrue(publicProfile(stranger, riley).metInPerson)
        }
    }

    @Test
    fun aCopyOfTheDatabaseDoesNotTieTwoMutualConfirmersTogether() {
        runBlocking {
            val a = fx.member("A")
            val b = fx.member("B")
            val chat = fx.talked(a, b)
            confirm(a, chat, b)
            confirm(b, chat, a)
            val rows =
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT * FROM vouches").use { rs ->
                            val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                            buildList { while (rs.next()) add(columns.associateWith { rs.getString(it) }) }
                        }
                    }
                }
            assertEquals(2, rows.size)
            val text = rows.toString()
            listOf(a, b, chat).forEach { assertFalse(text.contains(it.toString()), "no id in clear") }
            listOf("pair_token", "subject_token").forEach { column ->
                assertTrue(rows[0][column] != rows[1][column], "$column differs between the two rows")
            }
            val tokensOfFirst = rows[0].filterKeys { it.endsWith("_token") }.values.filterNotNull().toSet()
            val tokensOfSecond = rows[1].filterKeys { it.endsWith("_token") }.values.filterNotNull().toSet()
            assertTrue(tokensOfFirst.intersect(tokensOfSecond).isEmpty(), "no shared token links the two rows")
        }
    }

    @Test
    fun aWrongKeyIsRefusedEverywhere() {
        runBlocking {
            val subject = fx.member("Sam")
            val voucher = fx.member("Alex")
            val chat = fx.talked(voucher, subject)
            confirm(voucher, chat, subject)
            val wrong = MetInPersonTokens("a-different-met-in-person-key-0123456789")
            val wrongService = MetInPersonService(dataSource, wrong, clock)
            assertEquals(
                MetInPersonService.NOT_AVAILABLE,
                assertFailsWith<MessagingException> { wrongService.offers(voucher, chat.toString()) }.code,
            )
            assertEquals(0, MetInPersonBadgeBatch(dataSource, wrong, clock, Random(1)).recomputeNow())
            assertEquals(1, fx.vouchRows())
        }
    }

    @Test
    fun nothingNotifiesAnyone() {
        runBlocking {
            val subject = fx.member("Sam")
            fx.optIn(subject)
            val before = fx.sideEffectRows()
            val vouchers = confirmedBy(subject, 3)
            service.offers(subject, vouchers[0].second.toString())
            withdraw(vouchers[0].first, vouchers[0].second, subject)
            settle()
            messaging.markConversationRead(subject, vouchers[0].second.toString())
            assertEquals(before, fx.sideEffectRows())
            assertEquals(0, fx.sideEffectRows())
        }
    }

    @Test
    fun deletingAnAccountRemovesWhatItGaveAndReceived() {
        runBlocking {
            val accountData = MetInPersonAccountData(tokens)
            val lifecycle = JdbcAccountLifecycleRepository(dataSource, ContactBlockTokens.localDevelopment(), metInPersonAccountData = accountData)
            val subject = fx.member("Sam")
            val vouchers = confirmedBy(subject, 3).map { it.first }
            val other = fx.member("Other")
            confirm(vouchers[0], fx.talked(vouchers[0], other), other)
            settle()
            assertEquals(4, fx.vouchRows())

            lifecycle.deleteAccount(vouchers[0])
            assertEquals(2, fx.vouchRows(), "both confirmations the deleted voucher gave are gone")

            lifecycle.deleteAccount(subject)
            assertEquals(0, fx.vouchRows())
            assertEquals(0, fx.count("SELECT count(*) FROM vouch_badges WHERE subject_user_id = ?", subject))
        }
    }

    @Test
    fun theExportListsWhatTheVoucherGaveAndOnlyTheSubjectsPublishedBadge() {
        runBlocking {
            val archiver = JdbcAccountDataExportArchiver(dataSource, clock, ContactBlockTokens.localDevelopment(), MetInPersonAccountData(tokens))
            val subject = fx.member("Sam")
            fx.optIn(subject)
            val vouchers = confirmedBy(subject, 3).map { it.first }
            settle()

            val voucherExport = archiver.buildArchive(vouchers[0]).bytes.decodeToString()
            assertTrue(voucherExport.contains("\"subjectDisplayName\": \"Sam\""), voucherExport)

            val subjectExport = archiver.buildArchive(subject).bytes.decodeToString()
            assertTrue(subjectExport.contains("\"metInPersonBadge\": true"), subjectExport)
            assertTrue(subjectExport.contains("\"showMetInPersonBadge\": true"))
            vouchers.forEach { assertFalse(subjectExport.contains(it.toString()), "the subject never learns who confirmed") }
            assertFalse(subjectExport.contains("Voucher 1"))
        }
    }

    @Test
    fun anAllFalseBadgeRowIsNotKept() {
        runBlocking {
            val subject = fx.member("Sam")
            val vouchers = confirmedBy(subject, 3)
            settle()
            assertEquals(1, fx.count("SELECT count(*) FROM vouch_badges WHERE subject_user_id = ?", subject))
            vouchers.forEach { (voucher, chat) -> withdraw(voucher, chat, subject) }
            settle()
            assertEquals(0, fx.count("SELECT count(*) FROM vouch_badges WHERE subject_user_id = ?", subject))
            assertEquals(0, fx.count("SELECT count(*) FROM vouch_badge_states"))
        }
    }

    @Test
    fun theWeeklyPassRunsOnceUnderConcurrency() {
        runBlocking {
            val subject = fx.member("Sam")
            confirmedBy(subject, 3)
            service.offers(subject, fx.talked(subject, fx.member("Key")).toString())
            dataSource.connection.use { connection ->
                connection.prepareStatement("INSERT INTO vouch_batch_schedule (id, next_run_at) VALUES (1, ?)").use {
                    it.setTimestamp(1, Timestamp.from(clock.instant().minus(Duration.ofHours(1))))
                    it.executeUpdate()
                }
            }
            val (first, second) = raceTogether({ batch.runIfDue() }, { batch.runIfDue() })
            assertEquals(1, listOf(first.getOrThrow(), second.getOrThrow()).count { it })
            assertFalse(batch.runIfDue(), "not due again until next week's random time")
            val next =
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT next_run_at FROM vouch_batch_schedule WHERE id = 1").use { rs ->
                            rs.next()
                            rs.getTimestamp(1).toInstant()
                        }
                    }
                }
            val nextWeek = MetInPersonBadgeBatch.weekStart(clock.instant()).plus(MetInPersonBadgeBatch.WEEK)
            assertTrue(!next.isBefore(nextWeek) && next.isBefore(nextWeek.plus(MetInPersonBadgeBatch.WEEK)), "random time inside next week: $next")
        }
    }

    @Test
    fun theFirstScheduleIsARandomTimeInsideTheCurrentWeek() {
        val start = MetInPersonBadgeBatch.weekStart(clock.instant())
        repeat(20) {
            val at = batch.randomTimeInWeekOf(clock.instant())
            assertTrue(!at.isBefore(start) && at.isBefore(start.plus(MetInPersonBadgeBatch.WEEK)))
        }
    }
}
