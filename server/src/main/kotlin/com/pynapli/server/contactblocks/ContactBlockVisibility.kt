package com.pynapli.server.contactblocks

import com.pynapli.server.db.withConnection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The viewer-side half of "Hide from people you know". ONE-WAY: it answers "who must never appear to
 * this viewer", i.e. the members whose contact block is bound to the viewer. It is applied only to the
 * viewer's own reads (and to pushes and realtime events addressed to the viewer), never to what the
 * blocker sees: nothing the blocker can observe depends on it.
 *
 * Two kinds of row hide things from the viewer:
 *  - an ACTIVE bound row: the blocker never appears, and nothing they did, whenever, shows;
 *  - a TOMBSTONE (the blocker removed the address, V120 `released_at`): the blocker appears again,
 *    but what they did before `released_at` never shows ([ContactBlockViewerRules.hidesAct]).
 *
 * Not the ordinary block: `user_blocks` is two-way and BlockRepository's helpers hide both people from
 * each other, so they can't be reused here.
 */
interface ContactBlockVisibility {
    /** Everyone hiding from [viewerUserId] (active rows): drop them and their partner minis from the viewer's reads. */
    suspend fun hiddenFrom(viewerUserId: UUID): Set<UUID>

    /** True when [otherUserId] hides from [viewerUserId] (one direction only, active rows). */
    suspend fun isHiddenFrom(
        viewerUserId: UUID,
        otherUserId: UUID,
    ): Boolean

    /** Active rows and tombstones, for reads that filter dated acts in Kotlin. */
    suspend fun rulesFor(viewerUserId: UUID): ContactBlockViewerRules = ContactBlockViewerRules(hiddenFrom(viewerUserId), emptyMap())

    /**
     * [actorUserId] just did something [targetUserId] can see (a like, a flirt, a request, opening a
     * conversation). If the target hides from the actor (a bind that raced the act), the pair becomes
     * exempt from now on, as any act by the person they hide from would have made it at resolution.
     * The same single statement runs whatever the rows are.
     */
    suspend fun noteActToward(
        actorUserId: UUID,
        targetUserId: UUID,
    ) = Unit

    /** The viewer's lookup token, for SQL that applies [ContactBlockSql] fragments itself. */
    fun viewerToken(viewerUserId: UUID): String
}

/** What hides from one viewer: [hidden] (active rows) and [releasedAt] (tombstones: latest release per blocker). */
data class ContactBlockViewerRules(
    val hidden: Set<UUID>,
    val releasedAt: Map<UUID, Instant>,
) {
    /** True when the viewer must not be shown an act by [actorUserId] dated [at]. */
    fun hidesAct(
        actorUserId: UUID?,
        at: Instant,
    ): Boolean {
        if (actorUserId == null) return false
        if (actorUserId in hidden) return true
        val released = releasedAt[actorUserId] ?: return false
        return at.isBefore(released)
    }

    val isEmpty: Boolean get() = hidden.isEmpty() && releasedAt.isEmpty()
}

/** One HMAC and one indexed lookup per call (idx_contact_blocks_target). */
class JdbcContactBlockVisibility(
    private val dataSource: DataSource,
    private val tokens: ContactBlockTokens,
) : ContactBlockVisibility {
    override suspend fun hiddenFrom(viewerUserId: UUID): Set<UUID> =
        dataSource.withConnection { connection ->
            connection.prepareStatement(HIDDEN_FROM_SQL).use { statement ->
                statement.setString(1, tokens.targetToken(viewerUserId))
                statement.executeQuery().use { rs ->
                    buildSet {
                        while (rs.next()) add(rs.getObject("blocker_user_id", UUID::class.java))
                    }
                }
            }
        }

    override suspend fun isHiddenFrom(
        viewerUserId: UUID,
        otherUserId: UUID,
    ): Boolean {
        if (viewerUserId == otherUserId) return false
        return dataSource.withConnection { connection ->
            connection.prepareStatement(IS_HIDDEN_FROM_SQL).use { statement ->
                statement.setString(1, tokens.targetToken(viewerUserId))
                statement.setObject(2, otherUserId)
                statement.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
        }
    }

    override suspend fun rulesFor(viewerUserId: UUID): ContactBlockViewerRules =
        dataSource.withConnection { connection ->
            connection.prepareStatement(RULES_SQL).use { statement ->
                statement.setString(1, tokens.targetToken(viewerUserId))
                statement.executeQuery().use { rs ->
                    val hidden = mutableSetOf<UUID>()
                    val released = mutableMapOf<UUID, Instant>()
                    while (rs.next()) {
                        val blocker = rs.getObject("blocker_user_id", UUID::class.java)
                        val releasedAt = rs.getTimestamp("released_at")?.toInstant()
                        if (releasedAt == null) {
                            hidden += blocker
                        } else {
                            released.merge(blocker, releasedAt) { a, b -> if (a.isAfter(b)) a else b }
                        }
                    }
                    ContactBlockViewerRules(hidden, released)
                }
            }
        }

    override suspend fun noteActToward(
        actorUserId: UUID,
        targetUserId: UUID,
    ) {
        if (actorUserId == targetUserId) return
        dataSource.withConnection { connection ->
            connection.prepareStatement(EXEMPT_IF_BOUND_SQL).use { statement ->
                statement.setString(1, tokens.exemptToken(actorUserId))
                statement.setString(2, tokens.targetToken(actorUserId))
                statement.setObject(3, targetUserId)
                statement.executeUpdate()
            }
        }
    }

    override fun viewerToken(viewerUserId: UUID): String = tokens.targetToken(viewerUserId)

    private companion object {
        val HIDDEN_FROM_SQL =
            """
            SELECT blocker_user_id
            FROM contact_blocks
            WHERE target_token = ?
              AND released_at IS NULL
            """.trimIndent()

        val IS_HIDDEN_FROM_SQL =
            """
            SELECT EXISTS (
                SELECT 1
                FROM contact_blocks
                WHERE target_token = ?
                  AND blocker_user_id = ?
                  AND released_at IS NULL
            )
            """.trimIndent()

        val RULES_SQL =
            """
            SELECT blocker_user_id, released_at
            FROM contact_blocks
            WHERE target_token = ?
            """.trimIndent()

        // The row the TARGET added that hides them from the ACTOR (target_token = the actor's).
        val EXEMPT_IF_BOUND_SQL =
            """
            UPDATE contact_blocks
            SET target_token = ?
            WHERE target_token = ?
              AND blocker_user_id = ?
              AND released_at IS NULL
            """.trimIndent()
    }
}

/** SQL fragments for reads that must apply the viewer-side rule inside the query (paging, counts). */
object ContactBlockSql {
    /** Bound when no tokens are wired (unit tests): matches no row, so nothing is filtered. */
    const val NO_VIEWER_TOKEN = ""

    /**
     * "[userColumn] is not hiding from the viewer" (an active row; a person-level rule). Binds exactly
     * ONE parameter: the viewer's token ([ContactBlockVisibility.viewerToken], or [NO_VIEWER_TOKEN]).
     */
    fun notHiddenFromViewer(userColumn: String): String = "NOT EXISTS (SELECT 1 FROM contact_blocks hidden_cb WHERE hidden_cb.target_token = ? AND hidden_cb.blocker_user_id = $userColumn AND hidden_cb.released_at IS NULL)"

    /**
     * "An act by [userColumn] dated [atColumn] may be shown to the viewer": not while an active row
     * hides the actor, and never for acts dated before a tombstone's release. Binds exactly ONE
     * parameter: the viewer's token.
     */
    fun actNotHiddenFromViewer(
        userColumn: String,
        atColumn: String,
    ): String =
        "NOT EXISTS (SELECT 1 FROM contact_blocks hidden_cb WHERE hidden_cb.target_token = ? AND hidden_cb.blocker_user_id = $userColumn " +
            "AND (hidden_cb.released_at IS NULL OR $atColumn < hidden_cb.released_at))"
}

/** The viewer's token, or the no-op token when the feature isn't wired (unit tests). */
fun ContactBlockVisibility?.viewerTokenOrNone(viewerUserId: UUID): String = this?.viewerToken(viewerUserId) ?: ContactBlockSql.NO_VIEWER_TOKEN

fun ContactBlockTokens?.viewerTokenOrNone(viewerUserId: UUID): String = this?.targetToken(viewerUserId) ?: ContactBlockSql.NO_VIEWER_TOKEN
