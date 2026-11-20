package com.pynapli.server.metinperson

import com.pynapli.shared.network.dto.MetInPersonOfferDto
import com.pynapli.shared.network.dto.MetInPersonPartnerDto
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The "Met in person" rules (TRUST_AND_SAFETY_SPEC "Met in person"), as pure functions over facts the
 * repository loads, so the read-mark offer and the confirm write use exactly the same decision.
 *
 * What the offer may depend on: only facts the viewer already knows (their own account, their own
 * confirmations, the conversation's messages, whether they were ever linked with or blocked the
 * person, whether the person is photo-verified, which is public). Facts the viewer does not know (the
 * person blocked them, the two share a device, the person's risk tier) never change the offer; they
 * are applied silently by the weekly batch, so a confirmation that can never count looks exactly like
 * one that does.
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

    /** 24 months. */
    val EXPIRY: Duration = Duration.ofDays(730)

    val VOUCHER_RISK_TIERS = setOf("clean", "watch")

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
            !viewer.accountCreatedAt.isAfter(now.minus(MIN_ACCOUNT_AGE)) &&
            viewer.riskTier in VOUCHER_RISK_TIERS

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
    ): Boolean = isGiven(row, now) && !row!!.createdAt.isBefore(day(now).minus(WITHDRAW_WINDOW))

    /** How many cap slots writing this pair takes: a row already given inside the window is counted. */
    fun capSlotsNeeded(
        row: MetInPersonPairRow?,
        now: Instant,
    ): Int = if (row != null && !row.createdAt.isBefore(capWindowStart(now))) 0 else 1

    /**
     * The viewer's state for every other participant, actionable or not. [rows] holds the viewer's own
     * confirmations by subject id; [capUsed] counts what the viewer gave in the cap window (withdrawn
     * and voided rows included, so giving and taking back cannot reset the cap).
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
        return subjects.map { subject ->
            val row = rows[subject.subjectUserId]
            val given = isGiven(row, now)
            val slots = capSlotsNeeded(row, now)
            val canConfirm = !given && viewerOk && subjectQualifies(subject, now) && capRemaining >= slots
            val partner =
                if (!canConfirm) {
                    null
                } else {
                    subject.partnerUserId
                        ?.takeIf { subject.partnerShown && subject.partnerLinkId != null }
                        ?.let { byId[it] }
                        ?.takeIf { partnerFacts ->
                            val partnerRow = rows[partnerFacts.subjectUserId]
                            subjectQualifies(partnerFacts, now) &&
                                !isGiven(partnerRow, now) &&
                                capRemaining >= slots + capSlotsNeeded(partnerRow, now)
                        }?.let { MetInPersonPartnerDto(partnerUserId = it.subjectUserId.toString(), partnerDisplayName = it.displayName) }
                }
            MetInPersonOfferDto(
                subjectUserId = subject.subjectUserId.toString(),
                subjectDisplayName = subject.displayName,
                given = given,
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
    val verified: Boolean,
    val accountCreatedAt: Instant,
    val riskTier: String,
)

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
    /** Both partners' partner-visibility settings already show this link to the viewer. */
    val partnerShown: Boolean = false,
)

data class MetInPersonPairRow(
    val pairToken: String,
    val subjectUserId: UUID,
    val createdAt: Instant,
    val withdrawnAt: Instant?,
    val voidedAt: Instant?,
    val metPartnerUserId: UUID?,
    val linkId: UUID?,
)
