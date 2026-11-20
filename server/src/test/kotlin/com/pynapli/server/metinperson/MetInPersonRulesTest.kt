package com.pynapli.server.metinperson

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure write-time decision, the hysteresis, and the tokens. */
class MetInPersonRulesTest {
    private val now = Instant.parse("2026-11-18T12:00:00Z")
    private val viewer =
        MetInPersonViewerFacts(
            activeAccount = true,
            activeParticipant = true,
            participant = true,
            verified = true,
            accountCreatedAt = now.minus(Duration.ofDays(30)),
        )
    private val sam = UUID.randomUUID()
    private val riley = UUID.randomUUID()
    private val link = UUID.randomUUID()

    private fun subject(
        id: UUID = sam,
        name: String = "Sam",
        partner: UUID? = null,
        shown: Boolean = true,
    ) = MetInPersonSubjectFacts(
        subjectUserId = id,
        displayName = name,
        activeParticipant = true,
        verified = true,
        viewerSent = true,
        subjectSent = true,
        firstMessageAt = now.minus(Duration.ofHours(24)),
        lastMessageAt = now.minus(Duration.ofDays(90)),
        everLinkedWithViewer = false,
        viewerBlockedSubject = false,
        partnerUserId = partner,
        partnerLinkId = partner?.let { link },
        partnerShown = shown,
    )

    private fun row(
        createdAt: Instant = MetInPersonRules.day(now),
        withdrawnAt: Instant? = null,
        voidedAt: Instant? = null,
    ) = MetInPersonPairRow("token-${UUID.randomUUID()}", createdAt, withdrawnAt, voidedAt, null, null)

    private fun state(
        subjects: List<MetInPersonSubjectFacts> = listOf(subject()),
        rows: Map<UUID, MetInPersonPairRow> = emptyMap(),
        capUsed: Int = 0,
        viewerFacts: MetInPersonViewerFacts = viewer,
    ) = MetInPersonRules.states(viewerFacts, subjects, rows, capUsed, now).first()

    @Test
    fun theBoundariesAreInclusive() {
        assertTrue(state().canConfirm, "30 days old, 24 hours since the first message, 90 days since the last")
    }

    @Test
    fun eachViewerRuleIsEnforced() {
        assertFalse(state(viewerFacts = viewer.copy(verified = false)).canConfirm)
        assertFalse(state(viewerFacts = viewer.copy(accountCreatedAt = now.minus(Duration.ofDays(29)))).canConfirm)
        assertFalse(state(viewerFacts = viewer.copy(activeAccount = false)).canConfirm)
        assertFalse(state(viewerFacts = viewer.copy(activeParticipant = false)).canConfirm)
    }

    @Test
    fun eachConversationRuleIsEnforced() {
        val base = subject()
        listOf(
            base.copy(verified = false),
            base.copy(viewerSent = false),
            base.copy(subjectSent = false),
            base.copy(firstMessageAt = now.minus(Duration.ofHours(23))),
            base.copy(lastMessageAt = now.minus(Duration.ofDays(91))),
            base.copy(firstMessageAt = null, lastMessageAt = null),
            base.copy(everLinkedWithViewer = true),
            base.copy(viewerBlockedSubject = true),
            base.copy(activeParticipant = false),
        ).forEach { facts -> assertFalse(state(subjects = listOf(facts)).canConfirm, facts.toString()) }
    }

    @Test
    fun givenIsWithdrawableForThirtyDaysAndAVoidIsInvisible() {
        val given = state(rows = mapOf(sam to row()))
        assertTrue(given.given && given.canWithdraw && !given.canConfirm)
        assertEquals(given, state(rows = mapOf(sam to row(voidedAt = now))))
        val old = state(rows = mapOf(sam to row(createdAt = MetInPersonRules.day(now).minus(Duration.ofDays(31)))))
        assertTrue(old.given && !old.canWithdraw)
    }

    @Test
    fun takenBackMeans180DaysBeforeGivingAgain() {
        assertFalse(state(rows = mapOf(sam to row(withdrawnAt = MetInPersonRules.day(now)))).canConfirm)
        assertFalse(state(rows = mapOf(sam to row(withdrawnAt = MetInPersonRules.day(now).minus(Duration.ofDays(179))))).canConfirm)
        assertTrue(state(rows = mapOf(sam to row(withdrawnAt = MetInPersonRules.day(now).minus(Duration.ofDays(181))))).canConfirm)
        assertTrue(state(rows = mapOf(sam to row(createdAt = now.minus(Duration.ofDays(731))))).canConfirm, "expired")
    }

    @Test
    fun theCapIsNeverRefundedByAWithdrawal() {
        assertTrue(state(capUsed = MetInPersonRules.MONTHLY_CAP - 1).canConfirm)
        assertFalse(state(capUsed = MetInPersonRules.MONTHLY_CAP).canConfirm)
    }

    @Test
    fun thePartnerTickNeedsThePartnerHereQualifyingAndShownBothWays() {
        val withPartner = listOf(subject(partner = riley), subject(id = riley, name = "Riley", partner = sam))
        assertEquals("Riley", state(subjects = withPartner).partner?.partnerDisplayName)
        assertNull(state(subjects = listOf(subject(partner = riley))).partner, "not in this conversation")
        assertNull(state(subjects = listOf(subject(partner = riley, shown = false), withPartner[1])).partner, "Sam's rule hides it")
        assertNull(state(subjects = listOf(withPartner[0], withPartner[1].copy(partnerShown = false))).partner, "Riley's rule hides it")
        assertNull(state(subjects = listOf(withPartner[0], withPartner[1].copy(subjectSent = false))).partner)
        assertNull(state(subjects = withPartner, rows = mapOf(riley to row())).partner)
        assertNull(state(subjects = withPartner, capUsed = MetInPersonRules.MONTHLY_CAP - 1).partner)
    }

    @Test
    fun aCrossingOnTakesOneToFourExtraBatches() {
        listOf(1, 2, 3, 4).forEach { delay ->
            var track = BadgeTrack(published = false)
            var batches = 0
            while (!track.published) {
                track = MetInPersonHysteresis.step(track, qualifies = true, delay = { delay })
                batches++
                check(batches < 10)
            }
            assertEquals(1 + delay, batches)
        }
    }

    @Test
    fun aCrossingOffNeedsTwoBatchesBelowThenTheDelay() {
        listOf(1, 4).forEach { delay ->
            var track = BadgeTrack(published = true)
            var batches = 0
            while (track.published) {
                track = MetInPersonHysteresis.step(track, qualifies = false, delay = { delay })
                batches++
                check(batches < 12)
            }
            assertEquals(MetInPersonHysteresis.OFF_STREAK + delay, batches)
            assertTrue(track.idle)
        }
    }

    @Test
    fun aCrossingThatReversesIsCancelled() {
        val pending = MetInPersonHysteresis.step(BadgeTrack(published = false), qualifies = true, delay = { 3 })
        assertEquals(BadgeTrack(published = false), MetInPersonHysteresis.step(pending, qualifies = false, delay = { 3 }))
        val dipping = MetInPersonHysteresis.step(BadgeTrack(published = true), qualifies = false, delay = { 3 })
        assertEquals(BadgeTrack(published = true), MetInPersonHysteresis.step(dipping, qualifies = true, delay = { 3 }))
    }

    @Test
    fun tokensAreDirectedKeyedAndSeparatedByKind() {
        val tokens = MetInPersonTokens("key-one")
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        assertNotEquals(tokens.pair(a, b), tokens.pair(b, a))
        assertEquals(tokens.pair(a, b), MetInPersonTokens("key-one").pair(a, b))
        assertNotEquals(tokens.pair(a, b), MetInPersonTokens("key-two").pair(a, b))
        assertEquals(4, setOf(tokens.subject(a), tokens.partner(a), tokens.link(a), tokens.auditVoucher(a)).size)
        assertNotEquals(MetInPersonTokens("key-one").checkValue(), MetInPersonTokens("key-two").checkValue())
    }
}
