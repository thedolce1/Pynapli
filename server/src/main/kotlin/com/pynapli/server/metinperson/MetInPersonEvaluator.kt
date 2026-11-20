package com.pynapli.server.metinperson

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** A stored confirmation as the batch and the admin view read it: tokens only. */
internal data class StoredVouch(
    val id: UUID,
    val pairToken: String,
    val subjectToken: String,
    val partnerToken: String?,
    val linkToken: String?,
    val firstGivenAt: Instant,
    val createdAt: Instant,
    val withdrawnAt: Instant?,
    val voidedAt: Instant?,
) {
    fun live(now: Instant): Boolean = withdrawnAt == null && voidedAt == null && createdAt.isAfter(MetInPersonRules.expiryCutoff(now))
}

/** Why one stored confirmation counts or not at a batch (also shown to senior admins). */
internal data class RowVerdict(
    val vouch: StoredVouch,
    val voucherUserId: UUID?,
    val counts: Boolean,
)

internal data class SubjectEvaluation(
    val subjectUserId: UUID,
    val subjectEligible: Boolean,
    val rows: List<RowVerdict>,
    val countedVouchers: Set<UUID>,
    val independentVouchers: Int,
    val hasCleanVoucher: Boolean,
    val personQualifies: Boolean,
    val coupleVouchers: Set<UUID>,
    val coupleLinkId: UUID?,
    val coupleLinkToken: String?,
)

/**
 * Decides which confirmations count, for the weekly batch and the admin view
 * (TRUST_AND_SAFETY_SPEC "Met in person"). Rows hold only tokens, so a subject's rows are found by their
 * subject token and each row's voucher by recomputing pair tokens over the subject's conversation
 * partners (participant rows are never deleted). A token that matches nobody never counts.
 *
 * Every exclusion is something the voucher did, something the service did, or a fact fixed by the day
 * of the confirmation. Nothing the subject does afterwards (blocking, unblocking, hiding, reporting into
 * friction, inviting to link, confirming the voucher back) changes which confirmations count, so the
 * subject cannot use the badge to find out who confirmed them:
 *  - the voucher's account is active and photo-verified, and not quarantined or terminated (service; a
 *    single person's reports are capped below quarantine, so the subject alone cannot cause it);
 *  - the voucher has not blocked the subject (voucher);
 *  - no partner link between the two was active on or before the day given (fixed at that day; a link
 *    accepted later needs the voucher's own acceptance, and a pending invite never counts);
 *  - the two did not share a device on or before the day given (fixed at that day);
 *  - withdrawn (voucher), voided (service), expired after 24 months (time).
 * The subject's own state (active, verified, not quarantined/terminated) turns their whole badge off; it
 * never singles out a voucher.
 *
 * Independence: vouchers linked to each other (now or before) or sharing a device with each other count
 * once. At least one counted voucher must not have been confirmed by the subject first (or the same day),
 * so a same-day clique cannot badge itself; [StoredVouch.firstGivenAt] never moves, so a later act of the
 * subject cannot change this either.
 */
internal class MetInPersonEvaluator(
    private val tokens: MetInPersonTokens,
) {
    fun evaluate(
        connection: Connection,
        subjectUserIds: Collection<UUID>,
        now: Instant,
    ): Map<UUID, SubjectEvaluation> {
        if (subjectUserIds.isEmpty()) return emptyMap()
        val subjectByToken = subjectUserIds.associateBy { tokens.subject(it) }
        val rowsBySubject =
            connection
                .storedBySubjectTokens(subjectByToken.keys)
                .groupBy { subjectByToken.getValue(it.subjectToken) }
        if (rowsBySubject.isEmpty()) return emptyMap()
        val subjects = rowsBySubject.keys
        val partners = connection.conversationPartnersOf(subjects)

        // Resolve vouchers through their pair tokens.
        val voucherOf = mutableMapOf<UUID, UUID>()
        rowsBySubject.forEach { (subject, rows) ->
            val byPair = partners[subject].orEmpty().associateBy { tokens.pair(it, subject) }
            rows.forEach { row -> byPair[row.pairToken]?.let { voucherOf[row.id] = it } }
        }

        val links = connection.activeLinks(subjects)
        // Couple halves: the voucher's row about the partner, found by its pair token.
        val partnerHalfTokens =
            rowsBySubject.flatMap { (subject, rows) ->
                val link = links[subject] ?: return@flatMap emptyList<String>()
                rows.mapNotNull { row -> voucherOf[row.id]?.let { tokens.pair(it, link.partnerUserId) } }
            }
        val partnerHalves = connection.storedByPairTokens(partnerHalfTokens).associateBy { it.pairToken }

        // Per-row facts, both for the subject's own rows and for the couple halves.
        val queries = mutableListOf<FactQuery>()
        rowsBySubject.forEach { (subject, rows) ->
            rows.filter { it.live(now) }.forEach { row ->
                voucherOf[row.id]?.let { voucher -> queries += FactQuery(voucher, subject, row.createdAt, row.pairToken) }
            }
            links[subject]?.let { link ->
                rows.forEach { row ->
                    val voucher = voucherOf[row.id] ?: return@forEach
                    val half = partnerHalves[tokens.pair(voucher, link.partnerUserId)] ?: return@forEach
                    if (half.live(now)) queries += FactQuery(voucher, link.partnerUserId, half.createdAt, half.pairToken)
                }
            }
        }
        val passing = connection.passingFacts(queries)
        val eligibleSubjects = connection.eligibleSubjects(subjects + links.values.map { it.partnerUserId })

        val allVouchers = voucherOf.values.toSet()
        val edges = connection.voucherEdges(allVouchers)
        val firstGiven = connection.firstGivenByPair(firstGivenTokens(rowsBySubject, links, voucherOf))

        return rowsBySubject.mapValues { (subject, rows) ->
            val verdicts =
                rows.map { row ->
                    val voucher = voucherOf[row.id]
                    RowVerdict(
                        vouch = row,
                        voucherUserId = voucher,
                        counts = voucher != null && voucher != subject && row.live(now) && row.pairToken in passing,
                    )
                }
            val counted = verdicts.filter { it.counts }.mapNotNull { it.voucherUserId }.toSet()
            val subjectEligible = subject in eligibleSubjects
            val independent = components(counted, edges)
            val clean = counted.any { voucher -> isClean(voucher, listOf(subject), rows, firstGiven) }

            val link = links[subject]
            val coupleVouchers =
                if (link == null || !subjectEligible || link.partnerUserId !in eligibleSubjects) {
                    emptySet()
                } else {
                    val linkToken = tokens.link(link.linkId)
                    val partnerToken = tokens.partner(link.partnerUserId)
                    val subjectAsPartner = tokens.partner(subject)
                    verdicts
                        .filter { verdict ->
                            val row = verdict.vouch
                            verdict.counts &&
                                row.linkToken == linkToken &&
                                row.partnerToken == partnerToken &&
                                link.endedAt?.let { row.createdAt.isAfter(it) } != false
                        }.mapNotNull { verdict ->
                            val voucher = verdict.voucherUserId ?: return@mapNotNull null
                            val half = partnerHalves[tokens.pair(voucher, link.partnerUserId)] ?: return@mapNotNull null
                            voucher.takeIf {
                                half.live(now) &&
                                    half.pairToken in passing &&
                                    half.linkToken == linkToken &&
                                    half.partnerToken == subjectAsPartner &&
                                    link.endedAt?.let { half.createdAt.isAfter(it) } != false
                            }
                        }.toSet()
                }
            val coupleQualifies =
                coupleVouchers.isNotEmpty() &&
                    components(coupleVouchers, edges) >= MetInPersonRules.COUPLE_THRESHOLD &&
                    coupleVouchers.any { voucher -> isClean(voucher, listOfNotNull(subject, link?.partnerUserId), rows, firstGiven) }
            SubjectEvaluation(
                subjectUserId = subject,
                subjectEligible = subjectEligible,
                rows = verdicts,
                countedVouchers = counted,
                independentVouchers = independent,
                hasCleanVoucher = clean,
                personQualifies = subjectEligible && independent >= MetInPersonRules.PERSON_THRESHOLD && clean,
                coupleVouchers = coupleVouchers,
                coupleLinkId = link?.linkId?.takeIf { coupleQualifies },
                coupleLinkToken = link?.linkId?.takeIf { coupleQualifies }?.let(tokens::link),
            )
        }
    }

    private data class FactQuery(
        val voucher: UUID,
        val subject: UUID,
        val day: Instant,
        val pairToken: String,
    )

    internal data class ActiveLink(
        val partnerUserId: UUID,
        val linkId: UUID,
        val endedAt: Instant?,
    )

    /**
     * A voucher is "clean" for [subjects] unless one of them confirmed the voucher first (or the same
     * day). Uses first-given days, which never move.
     */
    private fun isClean(
        voucher: UUID,
        subjects: List<UUID>,
        subjectRows: List<StoredVouch>,
        firstGiven: Map<String, Instant>,
    ): Boolean {
        val voucherGave = subjectRows.firstOrNull { it.pairToken == tokens.pair(voucher, subjects.first()) }?.firstGivenAt ?: return false
        return subjects.none { subject ->
            val subjectGave = firstGiven[tokens.pair(subject, voucher)] ?: return@none false
            !subjectGave.isAfter(voucherGave)
        }
    }

    private fun firstGivenTokens(
        rowsBySubject: Map<UUID, List<StoredVouch>>,
        links: Map<UUID, ActiveLink>,
        voucherOf: Map<UUID, UUID>,
    ): List<String> =
        rowsBySubject.flatMap { (subject, rows) ->
            rows.mapNotNull { voucherOf[it.id] }.flatMap { voucher ->
                listOfNotNull(tokens.pair(subject, voucher), links[subject]?.let { tokens.pair(it.partnerUserId, voucher) })
            }
        }

    /** Connected components among [vouchers] (linked or device-sharing vouchers are one). */
    private fun components(
        vouchers: Set<UUID>,
        edges: Set<Pair<UUID, UUID>>,
    ): Int {
        val parent = vouchers.associateWith { it }.toMutableMap()

        fun find(x: UUID): UUID {
            var root = x
            while (parent.getValue(root) != root) root = parent.getValue(root)
            return root
        }
        edges.forEach { (a, b) ->
            if (a in parent && b in parent) {
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[ra] = rb
            }
        }
        return vouchers.map(::find).toSet().size
    }

    companion object {
        private const val STORED_COLUMNS =
            "id, pair_token, subject_token, partner_token, link_token, first_given_at, created_at, withdrawn_at, voided_at"

        fun Connection.storedBySubjectTokens(subjectTokens: Collection<String>): List<StoredVouch> {
            if (subjectTokens.isEmpty()) return emptyList()
            return prepareStatement("SELECT $STORED_COLUMNS FROM vouches WHERE subject_token = ANY(?) ORDER BY created_at, id").use { statement ->
                statement.setArray(1, createArrayOf("text", subjectTokens.toTypedArray()))
                statement.executeQuery().use { rs -> rs.readStored() }
            }
        }

        fun Connection.storedByPairTokens(pairTokens: Collection<String>): List<StoredVouch> {
            if (pairTokens.isEmpty()) return emptyList()
            return prepareStatement("SELECT $STORED_COLUMNS FROM vouches WHERE pair_token = ANY(?)").use { statement ->
                statement.setArray(1, createArrayOf("text", pairTokens.distinct().toTypedArray()))
                statement.executeQuery().use { rs -> rs.readStored() }
            }
        }

        fun Connection.conversationPartnersOf(userIds: Collection<UUID>): Map<UUID, Set<UUID>> {
            if (userIds.isEmpty()) return emptyMap()
            return prepareStatement(
                """
                SELECT DISTINCT a.user_id AS owner_id, b.user_id AS partner_id
                FROM conversation_participants a
                JOIN conversation_participants b
                  ON b.conversation_id = a.conversation_id
                 AND b.user_id <> a.user_id
                WHERE a.user_id = ANY(?)
                """.trimIndent(),
            ).use { statement ->
                statement.setArray(1, createArrayOf("uuid", userIds.toTypedArray()))
                statement.executeQuery().use { rs ->
                    val out = mutableMapOf<UUID, MutableSet<UUID>>()
                    while (rs.next()) {
                        out.getOrPut(rs.getObject("owner_id", UUID::class.java)) { mutableSetOf() }.add(rs.getObject("partner_id", UUID::class.java))
                    }
                    out
                }
            }
        }

        /** Each subject's active link: partner, canonical link row and when that row last ended. */
        fun Connection.activeLinks(userIds: Collection<UUID>): Map<UUID, ActiveLink> {
            if (userIds.isEmpty()) return emptyMap()
            return prepareStatement(
                """
                SELECT l.user_id,
                       l.linked_user_id,
                       canonical.id AS link_id,
                       ends.ended_at
                FROM user_profile_links l
                JOIN LATERAL (
                    SELECT c.id
                    FROM user_profile_links c
                    WHERE c.status = 'active'
                      AND c.unlinked_at IS NULL
                      AND (
                          (c.user_id = l.user_id AND c.linked_user_id = l.linked_user_id)
                          OR (c.user_id = l.linked_user_id AND c.linked_user_id = l.user_id)
                      )
                    ORDER BY c.id
                    LIMIT 1
                ) canonical ON true
                LEFT JOIN vouch_link_ends ends ON ends.link_id = canonical.id
                WHERE l.user_id = ANY(?)
                  AND l.status = 'active'
                  AND l.unlinked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setArray(1, createArrayOf("uuid", userIds.toTypedArray()))
                statement.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            put(
                                rs.getObject("user_id", UUID::class.java),
                                ActiveLink(
                                    partnerUserId = rs.getObject("linked_user_id", UUID::class.java),
                                    linkId = rs.getObject("link_id", UUID::class.java),
                                    endedAt = rs.getTimestamp("ended_at")?.toInstant(),
                                ),
                            )
                        }
                    }
                }
            }
        }

        /** Subjects whose own state allows a badge: active, photo-verified, not quarantined or terminated. */
        private fun Connection.eligibleSubjects(userIds: Collection<UUID>): Set<UUID> {
            if (userIds.isEmpty()) return emptySet()
            return prepareStatement(
                """
                SELECT u.id
                FROM users u
                JOIN profiles p ON p.user_id = u.id
                LEFT JOIN account_risk_states r ON r.user_id = u.id
                WHERE u.id = ANY(?)
                  AND u.status = 'active'
                  AND u.deleted_at IS NULL
                  AND p.verified_at IS NOT NULL
                  AND COALESCE(r.tier, 'clean') NOT IN ('quarantine', 'terminate')
                """.trimIndent(),
            ).use { statement ->
                statement.setArray(1, createArrayOf("uuid", userIds.distinct().toTypedArray()))
                statement.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
            }
        }

        /** Pair tokens of the (voucher, subject, day) facts that pass every per-row exclusion. */
        private fun Connection.passingFacts(queries: List<FactQuery>): Set<String> {
            if (queries.isEmpty()) return emptySet()
            return prepareStatement(
                """
                SELECT t.ord
                FROM unnest(?::uuid[], ?::uuid[], ?::bigint[]) WITH ORDINALITY AS t(voucher_id, subject_id, day_epoch, ord)
                JOIN users vu ON vu.id = t.voucher_id
                JOIN profiles vp ON vp.user_id = t.voucher_id
                LEFT JOIN account_risk_states vr ON vr.user_id = t.voucher_id
                WHERE vu.status = 'active'
                  AND vu.deleted_at IS NULL
                  AND vp.verified_at IS NOT NULL
                  AND COALESCE(vr.tier, 'clean') NOT IN ('quarantine', 'terminate')
                  -- The voucher's own block of the subject (never the subject's block of the voucher).
                  AND NOT EXISTS (
                      SELECT 1 FROM user_blocks b
                      WHERE b.blocker_user_id = t.voucher_id
                        AND b.blocked_user_id = t.subject_id
                  )
                  -- A partner link between the two that was active on or before the day given.
                  AND NOT EXISTS (
                      SELECT 1 FROM user_profile_links l
                      WHERE (
                              (l.user_id = t.voucher_id AND l.linked_user_id = t.subject_id)
                              OR (l.user_id = t.subject_id AND l.linked_user_id = t.voucher_id)
                            )
                        AND (
                              (l.accepted_at IS NOT NULL AND l.accepted_at < to_timestamp(t.day_epoch) + interval '1 day')
                              OR (l.accepted_at IS NULL AND l.status IN ('active', 'unlinked'))
                            )
                  )
                  -- A device both used on or before the day given.
                  AND NOT EXISTS (
                      SELECT 1
                      FROM account_device_links d1
                      JOIN account_device_links d2 ON d2.device_hash = d1.device_hash
                      WHERE d1.user_id = t.voucher_id
                        AND d2.user_id = t.subject_id
                        AND d1.created_at < to_timestamp(t.day_epoch) + interval '1 day'
                        AND d2.created_at < to_timestamp(t.day_epoch) + interval '1 day'
                  )
                """.trimIndent(),
            ).use { statement ->
                statement.setArray(1, createArrayOf("uuid", queries.map { it.voucher }.toTypedArray()))
                statement.setArray(2, createArrayOf("uuid", queries.map { it.subject }.toTypedArray()))
                statement.setArray(3, createArrayOf("bigint", queries.map { it.day.epochSecond }.toTypedArray()))
                statement.executeQuery().use { rs ->
                    buildSet { while (rs.next()) add(queries[(rs.getLong(1) - 1).toInt()].pairToken) }
                }
            }
        }

        /** Voucher pairs that count as one: linked (now or ever accepted) or sharing a device. */
        private fun Connection.voucherEdges(vouchers: Set<UUID>): Set<Pair<UUID, UUID>> {
            if (vouchers.size < 2) return emptySet()
            return prepareStatement(
                """
                SELECT l.user_id AS a, l.linked_user_id AS b
                FROM user_profile_links l
                WHERE l.user_id = ANY(?)
                  AND l.linked_user_id = ANY(?)
                  AND (l.accepted_at IS NOT NULL OR l.status IN ('active', 'unlinked'))
                UNION
                SELECT d1.user_id AS a, d2.user_id AS b
                FROM account_device_links d1
                JOIN account_device_links d2
                  ON d2.device_hash = d1.device_hash
                 AND d2.user_id <> d1.user_id
                WHERE d1.user_id = ANY(?)
                  AND d2.user_id = ANY(?)
                """.trimIndent(),
            ).use { statement ->
                val array = createArrayOf("uuid", vouchers.toTypedArray())
                (1..4).forEach { statement.setArray(it, array) }
                statement.executeQuery().use { rs ->
                    buildSet { while (rs.next()) add(rs.getObject("a", UUID::class.java) to rs.getObject("b", UUID::class.java)) }
                }
            }
        }

        private fun Connection.firstGivenByPair(pairTokens: Collection<String>): Map<String, Instant> {
            if (pairTokens.isEmpty()) return emptyMap()
            return prepareStatement("SELECT pair_token, first_given_at FROM vouches WHERE pair_token = ANY(?)").use { statement ->
                statement.setArray(1, createArrayOf("text", pairTokens.distinct().toTypedArray()))
                statement.executeQuery().use { rs ->
                    buildMap { while (rs.next()) put(rs.getString(1), rs.getTimestamp(2).toInstant()) }
                }
            }
        }

        private fun ResultSet.readStored(): List<StoredVouch> =
            buildList {
                while (next()) {
                    add(
                        StoredVouch(
                            id = getObject("id", UUID::class.java),
                            pairToken = getString("pair_token"),
                            subjectToken = getString("subject_token"),
                            partnerToken = getString("partner_token"),
                            linkToken = getString("link_token"),
                            firstGivenAt = getTimestamp("first_given_at").toInstant(),
                            createdAt = getTimestamp("created_at").toInstant(),
                            withdrawnAt = getTimestamp("withdrawn_at")?.toInstant(),
                            voidedAt = getTimestamp("voided_at")?.toInstant(),
                        ),
                    )
                }
            }    }
}
