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

/** The pure decision both the read-mark offer and the confirm write use. */
class MetInPersonRulesTest {
    private val now = Instant.parse("2026-11-18T12:00:00Z")
    private val viewer =
        MetInPersonViewerFacts(
            activeAccount = true,
            activeParticipant = true,
            verified = true,
            accountCreatedAt = now.minus(Duration.ofDays(30)),
            riskTier = "clean",
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
        subjectUserId: UUID = sam,
        createdAt: Instant = MetInPersonRules.day(now),
        withdrawnAt: Instant? = null,
        voidedAt: Instant? = null,
    ) = MetInPersonPairRow("token-$subjectUserId", subjectUserId, createdAt, withdrawnAt, voidedAt, null, null)

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
        assertFalse(state(viewerFacts = viewer.copy(riskTier = "friction")).canConfirm)
        assertFalse(state(viewerFacts = viewer.copy(riskTier = "quarantine")).canConfirm)
        assertTrue(state(viewerFacts = viewer.copy(riskTier = "watch")).canConfirm)
        assertFalse(state(viewerFacts = viewer.copy(activeAccount = false)).canConfirm)
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
    fun alreadyGivenIsWithdrawableForThirtyDaysAndAVoidIsInvisible() {
        val given = state(rows = mapOf(sam to row()))
        assertTrue(given.given)
        assertFalse(given.canConfirm)
        assertTrue(given.canWithdraw)
        assertEquals(given, state(rows = mapOf(sam to row(voidedAt = now))))
        val old = state(rows = mapOf(sam to row(createdAt = MetInPersonRules.day(now).minus(Duration.ofDays(31)))))
        assertTrue(old.given)
        assertFalse(old.canWithdraw)
    }

    @Test
    fun aWithdrawnOrExpiredPairCanBeGivenAgain() {
        assertTrue(state(rows = mapOf(sam to row(withdrawnAt = now))).canConfirm)
        assertTrue(state(rows = mapOf(sam to row(createdAt = now.minus(Duration.ofDays(731))))).canConfirm)
    }

    @Test
    fun theMonthlyCapCountsSlots() {
        assertTrue(state(capUsed = MetInPersonRules.MONTHLY_CAP - 1).canConfirm)
        assertFalse(state(capUsed = MetInPersonRules.MONTHLY_CAP).canConfirm)
        // Giving again a pair withdrawn inside the window takes no new slot.
        assertTrue(state(rows = mapOf(sam to row(withdrawnAt = now)), capUsed = MetInPersonRules.MONTHLY_CAP).canConfirm)
    }

    @Test
    fun thePartnerTickNeedsThePartnerHereQualifyingAndShown() {
        val withPartner = listOf(subject(partner = riley), subject(id = riley, name = "Riley", partner = sam))
        assertEquals("Riley", state(subjects = withPartner).partner?.partnerDisplayName)
        // Not in this conversation.
        assertNull(state(subjects = listOf(subject(partner = riley))).partner)
        // The link is hidden from this viewer.
        assertNull(state(subjects = listOf(subject(partner = riley, shown = false), withPartner[1])).partner)
        // The partner does not qualify on their own.
        assertNull(state(subjects = listOf(withPartner[0], withPartner[1].copy(subjectSent = false))).partner)
        // Already confirmed, or no room under the cap for both.
        assertNull(state(subjects = withPartner, rows = mapOf(riley to row(subjectUserId = riley))).partner)
        assertNull(state(subjects = withPartner, capUsed = MetInPersonRules.MONTHLY_CAP - 1).partner)
        assertTrue(state(subjects = withPartner, capUsed = MetInPersonRules.MONTHLY_CAP - 1).canConfirm)
    }

    @Test
    fun tokensAreDirectedAndKeyed() {
        val tokens = MetInPersonTokens("key-one")
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        assertNotEquals(tokens.pair(a, b), tokens.pair(b, a))
        assertEquals(tokens.pair(a, b), MetInPersonTokens("key-one").pair(a, b))
        assertNotEquals(tokens.pair(a, b), MetInPersonTokens("key-two").pair(a, b))
        assertNotEquals(tokens.voucher(a), tokens.conversation(a))
        assertFalse(tokens.voucher(a).contains(a.toString()))
    }
}
