package com.pynapli.server.contactblocks

import com.pynapli.server.db.withConnection
import java.util.UUID
import javax.sql.DataSource

/**
 * The viewer-side half of "Hide from people you know". ONE-WAY: it answers "who must never appear to
 * this viewer", i.e. the members whose contact block is bound to the viewer. It is applied only to the
 * viewer's own reads (and to pushes and realtime events addressed to the viewer), never to what the
 * blocker sees: nothing the blocker can observe depends on it.
 *
 * Not the ordinary block: `user_blocks` is two-way and BlockRepository's helpers hide both people from
 * each other, so they can't be reused here.
 */
interface ContactBlockVisibility {
    /** Everyone hiding from [viewerUserId]: drop them (and their partner minis) from the viewer's reads. */
    suspend fun hiddenFrom(viewerUserId: UUID): Set<UUID>

    /** True when [otherUserId] hides from [viewerUserId] (one direction only). */
    suspend fun isHiddenFrom(
        viewerUserId: UUID,
        otherUserId: UUID,
    ): Boolean

    /** The viewer's lookup token, for SQL that applies [ContactBlockSql.notHiddenFromViewer] itself. */
    fun viewerToken(viewerUserId: UUID): String
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

    override fun viewerToken(viewerUserId: UUID): String = tokens.targetToken(viewerUserId)

    private companion object {
        val HIDDEN_FROM_SQL =
            """
            SELECT blocker_user_id
            FROM contact_blocks
            WHERE target_token = ?
            """.trimIndent()

        val IS_HIDDEN_FROM_SQL =
            """
            SELECT EXISTS (
                SELECT 1
                FROM contact_blocks
                WHERE target_token = ?
                  AND blocker_user_id = ?
            )
            """.trimIndent()
    }
}

/** SQL fragments for reads that must apply the viewer-side rule inside the query (paging, counts). */
object ContactBlockSql {
    /** Bound when no tokens are wired (unit tests): matches no row, so nothing is filtered. */
    const val NO_VIEWER_TOKEN = ""

    /**
     * "[userColumn] is not hiding from the viewer". Binds exactly ONE parameter: the viewer's token
     * ([ContactBlockVisibility.viewerToken], or [NO_VIEWER_TOKEN]).
     */
    fun notHiddenFromViewer(userColumn: String): String =
        "NOT EXISTS (SELECT 1 FROM contact_blocks hidden_cb WHERE hidden_cb.target_token = ? AND hidden_cb.blocker_user_id = $userColumn)"
}

/** The viewer's token, or the no-op token when the feature isn't wired (unit tests). */
fun ContactBlockVisibility?.viewerTokenOrNone(viewerUserId: UUID): String = this?.viewerToken(viewerUserId) ?: ContactBlockSql.NO_VIEWER_TOKEN

fun ContactBlockTokens?.viewerTokenOrNone(viewerUserId: UUID): String = this?.targetToken(viewerUserId) ?: ContactBlockSql.NO_VIEWER_TOKEN
