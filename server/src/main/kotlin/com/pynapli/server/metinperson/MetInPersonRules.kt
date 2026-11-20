package com.pynapli.server.metinperson

import com.pynapli.shared.network.dto.MetInPersonOfferDto
import com.pynapli.shared.network.dto.MetInPersonPartnerDto
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The "Met in person" write-time rules (TRUST_AND_SAFETY_SPEC "Met in person"), as pure functions over
 * facts the repository loads, so the menu offer and the confirm write make exactly the same decision.
 *
 * The offer depends only on facts the viewer already knows: their own account (not their risk tier,
 * which they are never shown), their own confirmations, the messages between the two of them, whether
 * they were ever linked with or blocked the person, and the person's public verified badge. Everything
 * the viewer does not know (the person blocked them, a shared device, either side's risk tier) is
 * applied silently by the weekly batch, so a confirmation that will never count looks exactly like one
 * that does.
 */
object MetInPersonRules {
    const val PERSON_THRESHOLD = 3
    const val COUPLE_THRESHOLD = 3
    const val MONTHLY_CAP = 10

    val MIN_ACCOUNT_AGE: Duration = Duration.ofDays(30)
    val MIN_SINCE_FIRST_MESSAGE: Duration = Duration.ofHours(24)
    val MAX_SINCE_LAST_MESSAGE: Duration = Duration.ofDays(90)
    val CAP_WINDOW: Duration = Duration.ofDays(30)
    val WITHDRAW_WINDOW: Duration = Duration.ofDays(30)

    /** After taking a confirmation back, the same pair cannot give it again for this long. */
    val REGIVE_WAIT: Duration = Duration.ofDays(180)

    /** 24 months. */
    val EXPIRY: Duration = Duration.ofDays(730)

    /** Risk tiers whose confirmations count at the batch (applied there, never at the offer). */
    val COUNTED_VOUCHER_RISK_EXCLUDED = setOf("quarantine", "terminate")

    /** Stored dates are whole UTC days, so a confirmation cannot be lined up with a message or a session. */
    fun day(now: Instant): Instant = now.truncatedTo(ChronoUnit.DAYS)

    fun capWindowStart(now: Instant): Instant = day(now).minus(CAP_WINDOW)

    fun expiryCutoff(now: Instant): Instant = now.minus(EXPIRY)

    fun viewerQualifies(
        viewer: MetInPersonViewerFacts,
        now: Instant,
    ): Boolean =
        viewer.activeAccount &&
            viewer.activeParticipant &&
            viewer.verified &&
            !viewer.accountCreatedAt.isAfter(now.minus(MIN_ACCOUNT_AGE))

    fun subjectQualifies(
        subject: MetInPersonSubjectFacts,
        now: Instant,
    ): Boolean {
        val first = subject.firstMessageAt ?: return false
        val last = subject.lastMessageAt ?: return false
        return subject.activeParticipant &&
            subject.verified &&
            subject.viewerSent &&
            subject.subjectSent &&
            !first.isAfter(now.minus(MIN_SINCE_FIRST_MESSAGE)) &&
            !last.isBefore(now.minus(MAX_SINCE_LAST_MESSAGE)) &&
            !subject.everLinkedWithViewer &&
            !subject.viewerBlockedSubject
    }

    /** The viewer's confirmation of this person stands (an admin void is deliberately not visible here). */
    fun isGiven(
        row: MetInPersonPairRow?,
        now: Instant,
    ): Boolean = row != null && row.withdrawnAt == null && row.createdAt.isAfter(expiryCutoff(now))

    fun canWithdraw(
        row: MetInPersonPairRow?,
        now: Instant,
    ): Boolean = row != null && isGiven(row, now) && !row.createdAt.isBefore(day(now).minus(WITHDRAW_WINDOW))

    /** Taken back less than 180 days ago: the pair cannot give it again yet. */
    fun recentlyWithdrawn(
        row: MetInPersonPairRow?,
        now: Instant,
    ): Boolean = row?.withdrawnAt?.isAfter(day(now).minus(REGIVE_WAIT)) == true

    /**
     * The viewer's state for every other participant, actionable or not. [rows] holds the viewer's own
     * confirmations by subject id; [capUsed] counts every confirmation the viewer gave in the cap window,
     * withdrawn and voided ones included, so taking one back never refunds its slot.
     */
    fun states(
        viewer: MetInPersonViewerFacts,
        subjects: List<MetInPersonSubjectFacts>,
        rows: Map<UUID, MetInPersonPairRow>,
        capUsed: Int,
        now: Instant,
    ): List<MetInPersonOfferDto> {
        val viewerOk = viewerQualifies(viewer, now)
        val capRemaining = MONTHLY_CAP - capUsed
        val byId = subjects.associateBy { it.subjectUserId }
        fun confirmable(
            facts: MetInPersonSubjectFacts,
            row: MetInPersonPairRow?,
        ): Boolean = !isGiven(row, now) && !recentlyWithdrawn(row, now) && subjectQualifies(facts, now)
        return subjects.map { subject ->
            val row = rows[subject.subjectUserId]
            val canConfirm = viewerOk && capRemaining >= 1 && confirmable(subject, row)
            val partner =
                if (!canConfirm || capRemaining < 2) {
                    null
                } else {
                    subject.partnerUserId
                        ?.takeIf { subject.partnerShown && subject.partnerLinkId != null }
                        ?.let { byId[it] }
                        // Both partners' own profile rules show the link to this viewer.
                        ?.takeIf { partnerFacts ->
                            partnerFacts.partnerUserId == subject.subjectUserId &&
                                partnerFacts.partnerShown &&
                                confirmable(partnerFacts, rows[partnerFacts.subjectUserId])
                        }
                        ?.let { MetInPersonPartnerDto(partnerUserId = it.subjectUserId.toString(), partnerDisplayName = it.displayName) }
                }
            MetInPersonOfferDto(
                subjectUserId = subject.subjectUserId.toString(),
                subjectDisplayName = subject.displayName,
                given = isGiven(row, now),
                canConfirm = canConfirm,
                canWithdraw = canWithdraw(row, now),
                partner = partner,
            )
        }
    }

    /** Only states the viewer can act on reach the conversation menu. */
    fun actionable(state: MetInPersonOfferDto): Boolean = state.canConfirm || state.canWithdraw
}

data class MetInPersonViewerFacts(
    val activeAccount: Boolean,
    val activeParticipant: Boolean,
    /** Any participant row, left or not: enough to take a confirmation back. */
    val participant: Boolean,
    val verified: Boolean,
    val accountCreatedAt: Instant,
)

/**
 * Facts about one other participant relative to the viewer. Messages count only between the two of
 * them: each must have sent a visible message after both were in the conversation; first and last are
 * the earliest and latest such messages by either of them.
 */
data class MetInPersonSubjectFacts(
    val subjectUserId: UUID,
    val displayName: String,
    val activeParticipant: Boolean,
    val verified: Boolean,
    val viewerSent: Boolean,
    val subjectSent: Boolean,
    val firstMessageAt: Instant?,
    val lastMessageAt: Instant?,
    val everLinkedWithViewer: Boolean,
    val viewerBlockedSubject: Boolean,
    /** The person's active linked partner, if any; offered as "and [partner]" only when also here. */
    val partnerUserId: UUID? = null,
    val partnerLinkId: UUID? = null,
    /** The profile's own linked-partner rule shows this partner to the viewer. */
    val partnerShown: Boolean = false,
)

data class MetInPersonPairRow(
    val pairToken: String,
    val createdAt: Instant,
    val withdrawnAt: Instant?,
    val voidedAt: Instant?,
    val partnerToken: String?,
    val linkToken: String?,
)
