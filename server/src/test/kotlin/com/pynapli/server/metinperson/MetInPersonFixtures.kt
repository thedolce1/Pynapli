package com.pynapli.server.metinperson

import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** Test clock the met-in-person services read; every statement binds it, so tests drive time. */
internal class MetInPersonTestClock(
    private var now: Instant,
) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}

/** Plain-SQL fixtures with explicit timestamps (account age and message times are rules here). */
internal class MetInPersonFixtures(
    private val dataSource: DataSource,
    private val clock: Clock,
) {
    private fun now(): Instant = clock.instant()

    private fun exec(
        sql: String,
        vararg params: Any?,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { index, value ->
                    when (value) {
                        is Instant -> statement.setTimestamp(index + 1, Timestamp.from(value))
                        null -> statement.setObject(index + 1, null)
                        else -> statement.setObject(index + 1, value)
                    }
                }
                statement.executeUpdate()
            }
        }

    fun count(
        sql: String,
        vararg params: Any?,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun queryText(
        sql: String,
        vararg params: Any?,
    ): String =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rs ->
                    check(rs.next()) { "no row" }
                    rs.getString(1)
                }
            }
        }

    /** An active, approved, visible member: photo-verified and 60 days old unless told otherwise. */
    fun member(
        name: String,
        verified: Boolean = true,
        accountAge: Duration = Duration.ofDays(60),
        riskTier: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        exec("INSERT INTO users (id, status, created_at) VALUES (?, 'active', ?)", id, now().minus(accountAge))
        exec(
            """
            INSERT INTO profiles (user_id, display_name, display_age, moderation_status, visibility_status, verified_at)
            VALUES (?, ?, 30, 'approved', 'visible', ?)
            """.trimIndent(),
            id,
            name,
            if (verified) now().minus(Duration.ofDays(10)) else null,
        )
        exec("INSERT INTO discovery_settings (user_id) VALUES (?)", id)
        if (riskTier != null) setRiskTier(id, riskTier)
        return id
    }

    fun setRiskTier(
        userId: UUID,
        tier: String,
    ) {
        val score =
            when (tier) {
                "clean" -> 0
                "watch" -> 35
                "friction" -> 55
                "quarantine" -> 75
                else -> 95
            }
        exec(
            """
            INSERT INTO account_risk_states (user_id, score, tier) VALUES (?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET score = EXCLUDED.score, tier = EXCLUDED.tier
            """.trimIndent(),
            userId,
            score,
            tier,
        )
    }

    fun optIn(
        userId: UUID,
        on: Boolean = true,
    ) {
        exec("UPDATE discovery_settings SET show_met_in_person_badge = ? WHERE user_id = ?", on, userId)
    }

    /** A conversation with [members] as active participants (direct for two, group otherwise). */
    fun conversation(vararg members: UUID): UUID {
        val id = UUID.randomUUID()
        val kind = if (members.size == 2) "direct" else "group"
        exec(
            "INSERT INTO conversations (id, kind, origin, created_by_user_id, created_at, updated_at) VALUES (?, ?, 'grid', ?, ?, ?)",
            id,
            kind,
            members.first(),
            now().minus(Duration.ofDays(20)),
            now().minus(Duration.ofDays(20)),
        )
        if (members.size == 2) {
            exec(
                "INSERT INTO direct_conversations (conversation_id, user_a_id, user_b_id) VALUES (?, ?, ?)",
                id,
                members[0],
                members[1],
            )
        }
        members.forEach { member -> join(id, member, Duration.ofDays(20)) }
        return id
    }

    /** [member] joins [conversationId] as an active participant [ago] before now. */
    fun join(
        conversationId: UUID,
        member: UUID,
        ago: Duration,
    ) {
        exec(
            "INSERT INTO conversation_participants (conversation_id, user_id, joined_at) VALUES (?, ?, ?)",
            conversationId,
            member,
            now().minus(ago),
        )
    }

    fun leave(
        conversationId: UUID,
        member: UUID,
    ) {
        exec(
            "UPDATE conversation_participants SET invitation_status = 'left', left_at = ? WHERE conversation_id = ? AND user_id = ?",
            now(),
            conversationId,
            member,
        )
    }

    fun hide(
        hider: UUID,
        hidden: UUID,
    ) {
        exec("INSERT INTO user_hidden_profiles (id, hider_user_id, hidden_user_id) VALUES (?, ?, ?)", UUID.randomUUID(), hider, hidden)
    }

    /** A pending link invite from [from] to [to] (one row, never accepted). */
    fun inviteToLink(
        from: UUID,
        to: UUID,
    ) {
        exec(
            "INSERT INTO user_profile_links (id, user_id, linked_user_id, status) VALUES (?, ?, ?, 'pending')",
            UUID.randomUUID(),
            from,
            to,
        )
    }

    fun setDiscovery(
        userId: UUID,
        column: String,
        value: Any,
    ) {
        exec("UPDATE discovery_settings SET $column = ? WHERE user_id = ?", value, userId)
    }

    fun message(
        conversationId: UUID,
        sender: UUID,
        ago: Duration,
        moderationStatus: String = "visible",
    ) {
        exec(
            "INSERT INTO messages (id, conversation_id, sender_user_id, body, moderation_status, created_at) VALUES (?, ?, ?, 'hi', ?, ?)",
            UUID.randomUUID(),
            conversationId,
            sender,
            moderationStatus,
            now().minus(ago),
        )
    }

    /** A conversation where both people wrote, the first message 10 days ago and the last 2 days ago. */
    fun talked(
        a: UUID,
        b: UUID,
    ): UUID {
        val conversationId = conversation(a, b)
        message(conversationId, a, Duration.ofDays(10))
        message(conversationId, b, Duration.ofDays(2))
        return conversationId
    }

    fun link(
        a: UUID,
        b: UUID,
        status: String = "active",
    ) {
        listOf(a to b, b to a).forEach { (from, to) ->
            exec(
                """
                INSERT INTO user_profile_links (id, user_id, linked_user_id, status, accepted_at, unlinked_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                from,
                to,
                status,
                now().minus(Duration.ofDays(30)),
                if (status == "unlinked") now().minus(Duration.ofDays(5)) else null,
            )
        }
    }

    fun block(
        blocker: UUID,
        blocked: UUID,
    ) {
        exec("INSERT INTO user_blocks (id, blocker_user_id, blocked_user_id) VALUES (?, ?, ?)", UUID.randomUUID(), blocker, blocked)
    }

    /** Both used one device, [ago] before now (on the test clock). */
    fun shareDevice(
        a: UUID,
        b: UUID,
        ago: Duration = Duration.ofDays(10),
    ) {
        val hash = "device-${UUID.randomUUID()}"
        exec("INSERT INTO account_device_links (device_hash, user_id, created_at) VALUES (?, ?, ?)", hash, a, now().minus(ago))
        exec("INSERT INTO account_device_links (device_hash, user_id, created_at) VALUES (?, ?, ?)", hash, b, now().minus(ago))
    }

    fun vouchRows(): Int = count("SELECT count(*) FROM vouches")

    fun sideEffectRows(): Int =
        count("SELECT count(*) FROM notification_outbox") +
            count("SELECT count(*) FROM activity_events") +
            count("SELECT count(*) FROM match_side_effect_outbox")
}
