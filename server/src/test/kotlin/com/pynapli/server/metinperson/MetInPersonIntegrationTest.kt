package com.pynapli.server.metinperson

import com.pynapli.server.account.JdbcAccountDataExportArchiver
import com.pynapli.server.account.JdbcAccountLifecycleRepository
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
 * confirm whom, what the voucher sees, that nothing subject-facing or public changes between batches,
 * the weekly batch's thresholds and exclusions, the couple rule, deletion and export.
 */
class MetInPersonIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val clock = MetInPersonTestClock(Instant.parse("2026-11-18T12:00:00Z"))
    private val tokens = MetInPersonTokens("met-in-person-integration-key-0123456789")
    private val fx = MetInPersonFixtures(dataSource, clock)
    private val service = MetInPersonService(dataSource, tokens, clock)
    private val batch = MetInPersonBadgeBatch(dataSource, tokens, clock, Random(42))
    private val profiles = JdbcProfileRepository(dataSource)
    private val messaging = MessagingService(JdbcMessagingRepository(dataSource), metInPersonOfferSource = service)
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
    ): MetInPersonOfferDto? = service.offersFor(viewer, conversation).firstOrNull { it.subjectUserId == subject.toString() }

    private suspend fun publicProfile(
        viewer: UUID,
        subject: UUID,
    ): PublicProfileDto = assertNotNull(profiles.getPublicProfile(viewer, subject)).toDto(null)

    private suspend fun assertNotOffered(
        viewer: UUID,
        conversation: UUID,
        subject: UUID,
    ) {
        assertNull(offer(viewer, conversation, subject)?.takeIf { it.canConfirm })
        val refusal = assertFailsWith<MessagingException> { confirm(viewer, conversation, subject) }
        assertEquals(MetInPersonService.NOT_AVAILABLE, refusal.code)
        assertEquals(0, fx.vouchRows())
    }

    /** Three verified members who each talked with [subject] and confirm them. */
    private suspend fun confirmedBy(
        subject: UUID,
        count: Int,
    ): List<UUID> =
        (1..count).map { index ->
            val voucher = fx.member("Voucher $index")
            confirm(voucher, fx.talked(voucher, subject), subject)
            voucher
        }

    @Test
    fun aVerifiedMemberWhoTalkedBothWaysIsOfferedTheConfirmation() {
        runBlocking {
            val subject = fx.member("Sam")
            val voucher = fx.member("Alex")
            val conversation = fx.talked(voucher, subject)

            val state = assertNotNull(offer(voucher, conversation, subject))
            assertTrue(state.canConfirm)
            assertFalse(state.given)
            assertEquals("Sam", state.subjectDisplayName)
        }
    }

    @Test
    fun eachEligibilityRuleRefusesWithTheSameCode() {
        runBlocking {
            val subject = fx.member("Sam")
            // Unverified voucher.
            fx.member("Unverified", verified = false).let { assertNotOffered(it, fx.talked(it, subject), subject) }
            // Account younger than 30 days.
            fx.member("Young", accountAge = Duration.ofDays(29)).let { assertNotOffered(it, fx.talked(it, subject), subject) }
            // Risk tier past watch.
            fx.member("Friction", riskTier = "friction").let { assertNotOffered(it, fx.talked(it, subject), subject) }
            // Unverified subject.
            val unverifiedSubject = fx.member("Unverified subject", verified = false)
            fx.member("V1").let { assertNotOffered(it, fx.talked(it, unverifiedSubject), unverifiedSubject) }
            // Only the voucher wrote.
            fx.member("Monologue").let { voucher ->
                val conversation = fx.conversation(voucher, subject)
                fx.message(conversation, voucher, Duration.ofDays(5))
                assertNotOffered(voucher, conversation, subject)
            }
            // A held message does not count as visible.
            fx.member("Held").let { voucher ->
                val conversation = fx.conversation(voucher, subject)
                fx.message(conversation, voucher, Duration.ofDays(5))
                fx.message(conversation, subject, Duration.ofDays(4), moderationStatus = "held")
                assertNotOffered(voucher, conversation, subject)
            }
            // Under 24 hours since the first message.
            fx.member("Hasty").let { voucher ->
                val conversation = fx.conversation(voucher, subject)
                fx.message(conversation, voucher, Duration.ofHours(23))
                fx.message(conversation, subject, Duration.ofHours(22))
                assertNotOffered(voucher, conversation, subject)
            }
            // Over 90 days since the last message.
            fx.member("Stale").let { voucher ->
                val conversation = fx.conversation(voucher, subject)
                fx.message(conversation, voucher, Duration.ofDays(120))
                fx.message(conversation, subject, Duration.ofDays(91))
                assertNotOffered(voucher, conversation, subject)
            }
            // Current partner, former partner (any status).
            fx.member("Partner").let { voucher ->
                fx.link(voucher, subject)
                assertNotOffered(voucher, fx.talked(voucher, subject), subject)
            }
            fx.member("Ex").let { voucher ->
                fx.link(voucher, subject, status = "unlinked")
                assertNotOffered(voucher, fx.talked(voucher, subject), subject)
            }
            // The voucher blocked the subject.
            fx.member("Blocker").let { voucher ->
                val conversation = fx.talked(voucher, subject)
                fx.block(voucher, subject)
                assertNotOffered(voucher, conversation, subject)
            }
            // Watch is allowed.
            fx.member("Watched", riskTier = "watch").let { voucher ->
                assertTrue(assertNotNull(offer(voucher, fx.talked(voucher, subject), subject)).canConfirm)
            }
        }
    }

    @Test
    fun aPersonBadgeAppearsOnlyAtTheBatchAndOnlyAtThree() {
        runBlocking {
            val subject = fx.member("Sam")
            val stranger = fx.member("Stranger")
            fx.optIn(subject)
            confirmedBy(subject, 2)
            batch.recomputeNow()
            assertFalse(publicProfile(stranger, subject).metInPerson)

            val third = fx.member("Third")
            confirm(third, fx.talked(third, subject), subject)
            assertFalse(publicProfile(stranger, subject).metInPerson, "a confirmation never changes the badge by itself")

            batch.recomputeNow()
            assertTrue(publicProfile(stranger, subject).metInPerson)
        }
    }

    @Test
    fun theConfirmResponseIsTheSameWhetherOrNotItCompletesTheThreshold() {
        runBlocking {
            val subject = fx.member("Sam")
            val first = fx.member("V1")
            confirm(first, fx.talked(first, subject), subject)
            val second = fx.member("V2")
            val beforeThreshold = confirm(second, fx.talked(second, subject), subject)
            val third = fx.member("V3")
            val completesThreshold = confirm(third, fx.talked(third, subject), subject)

            assertEquals(
                json.encodeToString(MetInPersonOfferDto.serializer(), beforeThreshold),
                json.encodeToString(MetInPersonOfferDto.serializer(), completesThreshold),
            )
            assertTrue(completesThreshold.given && completesThreshold.canWithdraw && !completesThreshold.canConfirm)
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
            batch.recomputeNow()

            val publicBefore = json.encodeToString(PublicProfileDto.serializer(), publicProfile(stranger, subject))
            val meBefore = json.encodeToString(MeDto.serializer(), assertNotNull(profiles.getMe(subject)).toDto())
            val readBefore = readMarkJson(subject, friendConversation)
            val friendOfferBefore = offer(subject, friendConversation, friend)

            confirm(friend, friendConversation, subject)
            val vouchers = confirmedBy(subject, 5)

            assertEquals(publicBefore, json.encodeToString(PublicProfileDto.serializer(), publicProfile(stranger, subject)))
            assertEquals(meBefore, json.encodeToString(MeDto.serializer(), assertNotNull(profiles.getMe(subject)).toDto()))
            assertEquals(readBefore, readMarkJson(subject, friendConversation))
            assertEquals(friendOfferBefore, offer(subject, friendConversation, friend))

            // No response carries a voucher id or a count.
            val publicText = json.encodeToString(PublicProfileDto.serializer(), publicProfile(stranger, subject))
            (vouchers + friend).forEach { assertFalse(publicText.contains(it.toString())) }
            val meText = json.encodeToString(MeDto.serializer(), assertNotNull(profiles.getMe(subject)).toDto())
            (vouchers + friend).forEach { assertFalse(meText.contains(it.toString())) }
            listOf(publicText, meText, readMarkJson(subject, friendConversation)).forEach { text ->
                assertFalse(Regex("\"[A-Za-z]*([Vv]ouch|MetInPerson[A-Za-z]*Count|metInPersonCount)[A-Za-z]*\"\\s*:").containsMatchIn(text), text)
            }
            val metInPersonKeys = Json.parseToJsonElement(publicText).jsonObject.keys.filter { it.startsWith("metInPerson") }
            assertEquals(setOf("metInPerson", "metInPersonAsCouple"), metInPersonKeys.toSet())

            batch.recomputeNow()
            assertTrue(publicProfile(stranger, subject).metInPerson)
        }
    }

    private suspend fun readMarkJson(
        viewer: UUID,
        conversation: UUID,
    ): String {
        val dto = messaging.markConversationRead(viewer, conversation.toString())
        return json.encodeToString(ConversationDto.serializer(), dto)
    }

    @Test
    fun theOptInGatesTheBadge() {
        runBlocking {
            val subject = fx.member("Sam")
            val stranger = fx.member("Stranger")
            confirmedBy(subject, 3)
            batch.recomputeNow()
            assertFalse(publicProfile(stranger, subject).metInPerson, "off by default")
            fx.optIn(subject)
            assertTrue(publicProfile(stranger, subject).metInPerson)
            fx.optIn(subject, on = false)
            assertFalse(publicProfile(stranger, subject).metInPerson)
        }
    }

    @Test
    fun silentExclusionsAreAcceptedButNeverCount() {
        runBlocking {
            val subject = fx.member("Sam")
            val stranger = fx.member("Stranger")
            fx.optIn(subject)
            confirmedBy(subject, 2)
            // The subject blocked the voucher: the voucher cannot tell, and it never counts.
            val blockedBySubject = fx.member("Blocked")
            val blockedConversation = fx.talked(blockedBySubject, subject)
            fx.block(subject, blockedBySubject)
            assertTrue(assertNotNull(offer(blockedBySubject, blockedConversation, subject)).canConfirm)
            confirm(blockedBySubject, blockedConversation, subject)
            // A shared device: same.
            val sameDevice = fx.member("Same device")
            fx.shareDevice(sameDevice, subject)
            val deviceConversation = fx.talked(sameDevice, subject)
            assertTrue(assertNotNull(offer(sameDevice, deviceConversation, subject)).canConfirm)
            confirm(sameDevice, deviceConversation, subject)

            batch.recomputeNow()
            assertFalse(publicProfile(stranger, subject).metInPerson)
        }
    }

    @Test
    fun aRepeatChangesNothingAndThePairIsOneRowForever() {
        runBlocking {
            val subject = fx.member("Sam")
            val voucher = fx.member("Alex")
            val conversation = fx.talked(voucher, subject)
            val first = confirm(voucher, conversation, subject)
            val given = fx.count("SELECT count(*) FROM vouches WHERE created_at = ?", Timestamp.from(MetInPersonRules.day(clock.instant())))
            clock.advance(Duration.ofDays(2))
            assertEquals(first.copy(), confirm(voucher, conversation, subject))
            assertEquals(1, fx.vouchRows())
            assertEquals(given, fx.count("SELECT count(*) FROM vouches WHERE created_at = ?", Timestamp.from(MetInPersonRules.day(clock.instant().minus(Duration.ofDays(2))))))

            // Withdraw, then give again: still one row, counted once.
            assertFalse(withdraw(voucher, conversation, subject).given)
            assertTrue(confirm(voucher, conversation, subject).given)
            assertEquals(1, fx.vouchRows())
        }
    }

    @Test
    fun withdrawalWorksForThirtyDaysAndStopsTheConfirmationCounting() {
        runBlocking {
            val subject = fx.member("Sam")
            val stranger = fx.member("Stranger")
            fx.optIn(subject)
            val vouchers = (1..3).map { fx.member("V$it") }
            val conversations = vouchers.map { fx.talked(it, subject) }
            vouchers.zip(conversations).forEach { (voucher, conversation) -> confirm(voucher, conversation, subject) }
            batch.recomputeNow()
            assertTrue(publicProfile(stranger, subject).metInPerson)

            val taken = withdraw(vouchers[0], conversations[0], subject)
            assertFalse(taken.given)
            assertTrue(publicProfile(stranger, subject).metInPerson, "withdrawal is invisible until the batch")
            batch.recomputeNow()
            assertFalse(publicProfile(stranger, subject).metInPerson)

            // Past 30 days a withdrawal does nothing and says nothing.
            clock.advance(Duration.ofDays(31))
            val late = withdraw(vouchers[1], conversations[1], subject)
            assertTrue(late.given)
            assertFalse(late.canWithdraw)
        }
    }

    @Test
    fun theMonthlyCapStopsTheEleventhConfirmation() {
        runBlocking {
            val voucher = fx.member("Busy")
            repeat(MetInPersonRules.MONTHLY_CAP) { index ->
                val subject = fx.member("S$index")
                confirm(voucher, fx.talked(voucher, subject), subject)
            }
            val eleventh = fx.member("S11")
            val conversation = fx.talked(voucher, eleventh)
            assertNull(offer(voucher, conversation, eleventh))
            assertEquals(MetInPersonService.NOT_AVAILABLE, assertFailsWith<MessagingException> { confirm(voucher, conversation, eleventh) }.code)

            // The window rolls.
            clock.advance(Duration.ofDays(32))
            fx.message(conversation, voucher, Duration.ofDays(1))
            assertTrue(assertNotNull(offer(voucher, conversation, eleventh)).canConfirm)
        }
    }

    @Test
    fun quarantinedVouchersBlocksAfterAndExpiryDropTheBadgeAtTheNextBatch() {
        runBlocking {
            val subject = fx.member("Sam")
            val stranger = fx.member("Stranger")
            fx.optIn(subject)
            val vouchers = confirmedBy(subject, 3)
            batch.recomputeNow()
            assertTrue(publicProfile(stranger, subject).metInPerson)

            fx.setRiskTier(vouchers[0], "quarantine")
            assertTrue(publicProfile(stranger, subject).metInPerson, "only the batch changes it")
            batch.recomputeNow()
            assertFalse(publicProfile(stranger, subject).metInPerson)

            fx.setRiskTier(vouchers[0], "clean")
            batch.recomputeNow()
            assertTrue(publicProfile(stranger, subject).metInPerson)

            fx.block(vouchers[1], subject)
            batch.recomputeNow()
            assertFalse(publicProfile(stranger, subject).metInPerson)
        }
    }

    @Test
    fun confirmationsExpireAfterTwentyFourMonths() {
        runBlocking {
            val subject = fx.member("Sam")
            val stranger = fx.member("Stranger")
            fx.optIn(subject)
            confirmedBy(subject, 3)
            batch.recomputeNow()
            assertTrue(publicProfile(stranger, subject).metInPerson)

            clock.advance(Duration.ofDays(729))
            batch.recomputeNow()
            assertTrue(publicProfile(stranger, subject).metInPerson)
            clock.advance(Duration.ofDays(2))
            batch.recomputeNow()
            assertFalse(publicProfile(stranger, subject).metInPerson)
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
            val state = assertNotNull(offer(voucher, group, sam))
            assertEquals(riley.toString(), state.partner?.partnerUserId)
            confirm(voucher, group, sam, includePartner = true)
            assertTrue(assertNotNull(offer(voucher, group, riley)).given)
        }
    }

    @Test
    fun aCoupleBadgeNeedsThreeVouchersWhoEachMetBothAndBothOptIns() {
        runBlocking {
            val sam = fx.member("Sam")
            val riley = fx.member("Riley")
            val stranger = fx.member("Stranger")
            fx.link(sam, riley)
            fx.optIn(sam)
            coupleConfirmedBy(sam, riley, 2)
            batch.recomputeNow()
            assertFalse(publicProfile(stranger, sam).metInPersonAsCouple)

            coupleConfirmedBy(sam, riley, 1)
            batch.recomputeNow()
            assertTrue(publicProfile(stranger, sam).metInPerson)
            assertFalse(publicProfile(stranger, sam).metInPersonAsCouple, "Riley has not turned it on")

            fx.optIn(riley)
            assertTrue(publicProfile(stranger, sam).metInPersonAsCouple)
            assertTrue(publicProfile(stranger, riley).metInPersonAsCouple)
        }
    }

    @Test
    fun separateConfirmationsOfEachPartnerAreNotACoupleCredit() {
        runBlocking {
            val sam = fx.member("Sam")
            val riley = fx.member("Riley")
            val stranger = fx.member("Stranger")
            fx.link(sam, riley)
            fx.optIn(sam)
            fx.optIn(riley)
            repeat(3) { index ->
                val voucher = fx.member("Separate $index")
                confirm(voucher, fx.talked(voucher, sam), sam)
                confirm(voucher, fx.talked(voucher, riley), riley)
            }
            batch.recomputeNow()
            assertTrue(publicProfile(stranger, sam).metInPerson)
            assertFalse(publicProfile(stranger, sam).metInPersonAsCouple)
        }
    }

    @Test
    fun unlinkingVoidsOnlyTheCoupleBadgeAndARelinkDoesNotRevive() {
        runBlocking {
            val sam = fx.member("Sam")
            val riley = fx.member("Riley")
            val stranger = fx.member("Stranger")
            fx.link(sam, riley)
            fx.optIn(sam)
            fx.optIn(riley)
            coupleConfirmedBy(sam, riley, 3)
            batch.recomputeNow()
            assertTrue(publicProfile(stranger, sam).metInPersonAsCouple)

            assertTrue(JdbcProfileLinkRepository(dataSource).unlinkCurrent(sam))
            assertFalse(publicProfile(stranger, sam).metInPersonAsCouple)
            assertTrue(publicProfile(stranger, sam).metInPerson)
            batch.recomputeNow()
            assertFalse(publicProfile(stranger, sam).metInPersonAsCouple)
            assertTrue(publicProfile(stranger, riley).metInPerson)

            // Relinking reuses the same link rows; the old couple confirmations stay void.
            dataSource.connection.use { connection ->
                connection.createStatement().use {
                    it.executeUpdate("UPDATE user_profile_links SET status = 'active', unlinked_at = NULL")
                }
            }
            batch.recomputeNow()
            assertFalse(publicProfile(stranger, sam).metInPersonAsCouple)
            assertTrue(publicProfile(stranger, sam).metInPerson)
        }
    }

    @Test
    fun nothingNotifiesAnyone() {
        runBlocking {
            val subject = fx.member("Sam")
            fx.optIn(subject)
            val before = fx.sideEffectRows()
            val vouchers = (1..3).map { fx.member("V$it") }
            val conversations = vouchers.map { fx.talked(it, subject) }
            vouchers.zip(conversations).forEach { (voucher, conversation) -> confirm(voucher, conversation, subject) }
            service.offersFor(subject, conversations[0])
            withdraw(vouchers[0], conversations[0], subject)
            confirm(vouchers[0], conversations[0], subject)
            batch.recomputeNow()
            messaging.markConversationRead(subject, conversations[0].toString())
            assertEquals(before, fx.sideEffectRows())
            assertEquals(0, fx.sideEffectRows())
        }
    }

    @Test
    fun deletingAnAccountRemovesWhatItGaveAndReceived() {
        runBlocking {
            val accountData = MetInPersonAccountData(tokens)
            val lifecycle = JdbcAccountLifecycleRepository(dataSource, metInPersonAccountData = accountData)
            val subject = fx.member("Sam")
            val vouchers = confirmedBy(subject, 3)
            val other = fx.member("Other")
            confirm(vouchers[0], fx.talked(vouchers[0], other), other)
            batch.recomputeNow()
            assertEquals(4, fx.vouchRows())

            lifecycle.deleteAccount(vouchers[0])
            assertEquals(2, fx.vouchRows(), "both confirmations the deleted voucher gave are gone")

            lifecycle.deleteAccount(subject)
            assertEquals(0, fx.count("SELECT count(*) FROM vouches WHERE subject_user_id = ?", subject))
            assertEquals(0, fx.count("SELECT count(*) FROM vouch_badges WHERE subject_user_id = ?", subject))
        }
    }

    @Test
    fun theExportListsWhatTheVoucherGaveAndOnlyTheSubjectsBadgeState() {
        runBlocking {
            val accountData = MetInPersonAccountData(tokens)
            val archiver = JdbcAccountDataExportArchiver(dataSource, clock, accountData)
            val subject = fx.member("Sam")
            fx.optIn(subject)
            val vouchers = confirmedBy(subject, 3)
            batch.recomputeNow()

            val voucherExport = archiver.buildArchive(vouchers[0]).bytes.decodeToString()
            assertTrue(voucherExport.contains("\"subjectDisplayName\": \"Sam\""), voucherExport)
            assertFalse(voucherExport.contains("\"subjectUserId\""))

            val subjectExport = archiver.buildArchive(subject).bytes.decodeToString()
            assertTrue(subjectExport.contains("\"metInPersonBadge\": true"), subjectExport)
            assertTrue(subjectExport.contains("\"showMetInPersonBadge\": true"))
            vouchers.forEach { assertFalse(subjectExport.contains(it.toString()), "the subject never learns who confirmed") }
            assertFalse(subjectExport.contains("Voucher 1"))
        }
    }

    @Test
    fun theWeeklyBatchRunsOnceUnderConcurrency() {
        runBlocking {
            val subject = fx.member("Sam")
            fx.optIn(subject)
            confirmedBy(subject, 3)
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
