package com.pynapli.server.contactblocks

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.lockPairForTransaction
import com.pynapli.server.db.withConnection
import com.pynapli.server.swipe.SWIPE_PAIR_LOCK_NAMESPACE
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
     * exempt from now on, as any act by the person they hide from would have made it at resolution:
     * the bound row becomes a tombstone (what the target did while hidden stays hidden from the actor)
     * and an exempt row replaces it. Runs in its own transaction under the pair advisory lock the bind
     * holds, AFTER the act committed, so either the bind's check saw the act or this sees the bind.
     * Call it on every path where the actor does something the target sees.
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
            connection.inTransaction {
                // The same pair lock the bind holds from its interaction check to its commit: if the
                // bind is in flight this waits and then finds the bound row; if it isn't, the bind's
                // check (which runs after this act committed) sees the act and settles exempt.
                lockPairForTransaction(SWIPE_PAIR_LOCK_NAMESPACE, actorUserId, targetUserId)
                prepareStatement(FLIP_TO_EXEMPT_SQL).use { statement ->
                    statement.setTimestamp(1, java.sql.Timestamp.from(Instant.now()))
                    statement.setString(2, tokens.targetToken(actorUserId))
                    statement.setObject(3, targetUserId)
                    statement.setString(4, tokens.exemptToken(actorUserId))
                    statement.executeQuery().close()
                }
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

        // The row the TARGET added that hides them from the ACTOR (target_token = the actor's) becomes a
        // tombstone at the flip, so everything the target did while hidden stays hidden, and an exempt
        // row takes its place (same address, so the blocker's count doesn't move). Older tombstones of
        // the same pair beyond the newest few go ([TOMBSTONES_KEPT_BEFORE_NEW]).
        val FLIP_TO_EXEMPT_SQL =
            """
            WITH flipped AS (
                UPDATE contact_blocks
                SET released_at = ?::timestamptz
                WHERE target_token = ?
                  AND blocker_user_id = ?
                  AND released_at IS NULL
                RETURNING id, blocker_user_id, email_token, target_token
            ),
            pruned AS (
                DELETE FROM contact_blocks prior
                USING flipped
                WHERE prior.blocker_user_id = flipped.blocker_user_id
                  AND prior.target_token = flipped.target_token
                  AND prior.released_at IS NOT NULL
                  AND prior.id <> flipped.id
                  -- Keep the newest few: their windows still tell 'a match X was never shown' apart (Interaction).
                  AND prior.id NOT IN (
                      SELECT keep.id FROM contact_blocks keep
                      WHERE keep.blocker_user_id = prior.blocker_user_id AND keep.target_token = prior.target_token AND keep.released_at IS NOT NULL
                      ORDER BY keep.released_at DESC
                      LIMIT $TOMBSTONES_KEPT_BEFORE_NEW
                  )
                RETURNING 1
            )
            INSERT INTO contact_blocks (id, blocker_user_id, email_token, target_token, created_at)
            SELECT gen_random_uuid(), flipped.blocker_user_id, flipped.email_token, ?, now()
            FROM flipped
            RETURNING 1
            """.trimIndent()
    }
}

/**
 * Tombstone growth: every release (remove, remove all, race flip) of a (blocker, target) pair keeps
 * this many older tombstones of the pair plus the new one and deletes the rest. For hiding acts the
 * newest tombstone subsumes the older ones (it hides everything dated before a later time); the few
 * kept only preserve recent hidden windows for the interaction check (a match X was never shown).
 * So add/remove cycling one address leaves at most five rows for the pair however many cycles run;
 * more rows need distinct members, which the add caps bound.
 */
internal const val TOMBSTONES_KEPT_BEFORE_NEW = 4

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

/**
 * The hot-path early-out: the viewer's token when any row (active or released) targets them, else
 * [ContactBlockSql.NO_VIEWER_TOKEN], with which every per-row check is skipped or matches nothing. One
 * indexed lookup (idx_contact_blocks_target) per request.
 */
fun java.sql.Connection.viewerTokenIfTargeted(
    tokens: ContactBlockTokens,
    viewerUserId: UUID,
): String {
    val token = tokens.targetToken(viewerUserId)
    val targeted =
        prepareStatement("SELECT EXISTS (SELECT 1 FROM contact_blocks WHERE target_token = ?)").use { statement ->
            statement.setString(1, token)
            statement.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
    return if (targeted) token else ContactBlockSql.NO_VIEWER_TOKEN
}

suspend fun DataSource.viewerTokenIfTargeted(
    tokens: ContactBlockTokens,
    viewerUserId: UUID,
): String = withConnection { connection -> connection.viewerTokenIfTargeted(tokens, viewerUserId) }

/** The viewer's token, or the no-op token when the feature isn't wired (unit tests). */
fun ContactBlockVisibility?.viewerTokenOrNone(viewerUserId: UUID): String = this?.viewerToken(viewerUserId) ?: ContactBlockSql.NO_VIEWER_TOKEN

fun ContactBlockTokens?.viewerTokenOrNone(viewerUserId: UUID): String = this?.targetToken(viewerUserId) ?: ContactBlockSql.NO_VIEWER_TOKEN
