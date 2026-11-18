package com.pynapli.server.moderation

import java.sql.Connection
import java.util.UUID

internal fun Connection.selectRiskStates(
    tierFilter: String,
    limit: Int,
): List<AdminRiskStateRecord> =
    prepareStatement(
        """
        SELECT ars.user_id,
               NULLIF(p.display_name, '') AS display_name,
               ars.score,
               ars.tier,
               ars.last_event_at,
               ars.last_reviewed_at,
               ars.updated_at
        FROM account_risk_states ars
        JOIN users u
          ON u.id = ars.user_id
         AND u.deleted_at IS NULL
        LEFT JOIN profiles p ON p.user_id = ars.user_id
        WHERE (
            ? = 'all'
            OR (? = 'needs_review' AND ars.tier <> 'clean')
            OR ars.tier = ?
        )
        ORDER BY
            CASE ars.tier
                WHEN 'terminate' THEN 0
                WHEN 'quarantine' THEN 1
                WHEN 'friction' THEN 2
                WHEN 'watch' THEN 3
                ELSE 4
            END ASC,
            ars.updated_at DESC,
            ars.user_id ASC
        LIMIT ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, tierFilter)
        statement.setString(2, tierFilter)
        statement.setString(3, tierFilter)
        statement.setInt(4, limit)
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(rs.toAdminRiskStateRecord())
            }
        }
    }

internal fun Connection.selectRiskEvents(
    userId: UUID,
    limit: Int,
): List<AdminRiskEventRecord> =
    prepareStatement(
        """
        SELECT id,
               user_id,
               actor_user_id,
               source,
               reason,
               delta,
               score_after,
               tier_after,
               metadata::text AS metadata_json,
               created_at
        FROM account_risk_events
        WHERE user_id = ?
        ORDER BY created_at DESC, id DESC
        LIMIT ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setInt(2, limit)
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(rs.toAdminRiskEventRecord())
            }
        }
    }

internal fun Connection.activeOrPausedUserExists(userId: UUID): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1
            FROM users
            WHERE id = ?
              AND deleted_at IS NULL
              AND status IN ('active', 'paused', 'suspended')
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

internal fun Connection.lockUserStatus(userId: UUID): String? =
    prepareStatement(
        """
        SELECT status
        FROM users
        WHERE id = ?
          AND deleted_at IS NULL
          AND status <> 'deleted'
        FOR UPDATE
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.getString("status") else null
        }
    }

internal fun Connection.updateUserStatus(
    userId: UUID,
    status: String,
) {
    prepareStatement(
        """
        UPDATE users
        SET status = ?,
            updated_at = now()
        WHERE id = ?
          AND deleted_at IS NULL
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, status)
        statement.setObject(2, userId)
        statement.executeUpdate()
    }
}

internal fun Connection.revokeUserSessions(userId: UUID): Int =
    prepareStatement(
        """
        UPDATE user_sessions
        SET revoked_at = now()
        WHERE user_id = ?
          AND revoked_at IS NULL
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeUpdate()
    }

internal fun Connection.lockRiskScore(userId: UUID): Int? =
    prepareStatement(
        """
        SELECT score
        FROM account_risk_states
        WHERE user_id = ?
        FOR UPDATE
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.getInt("score") else null
        }
    }

internal fun Connection.lockRiskState(userId: UUID): AdminRiskActionRecord? =
    prepareStatement(
        """
        SELECT user_id,
               score,
               tier,
               last_reviewed_at,
               updated_at
        FROM account_risk_states
        WHERE user_id = ?
        FOR UPDATE
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toAdminRiskActionRecord() else null
        }
    }

internal fun Connection.upsertRiskState(
    userId: UUID,
    score: Int,
    tier: String,
    reviewed: Boolean,
): AdminRiskActionRecord =
    prepareStatement(
        """
        INSERT INTO account_risk_states (
            user_id,
            score,
            tier,
            last_event_at,
            last_reviewed_at,
            updated_at
        )
        VALUES (
            ?,
            ?,
            ?,
            now(),
            CASE WHEN ? THEN now() ELSE NULL END,
            now()
        )
        ON CONFLICT (user_id) DO UPDATE
        SET score = EXCLUDED.score,
            tier = EXCLUDED.tier,
            -- An admin-set score never decays (safety/RiskDecayPolicy): minor points recorded before
            -- the admin action are absorbed into it. Only minor points recorded after it can decay.
            decayable_points = 0,
            decay_anchor_at = NULL,
            last_event_at = now(),
            last_reviewed_at = CASE
                WHEN ? THEN now()
                ELSE account_risk_states.last_reviewed_at
            END,
            updated_at = now()
        RETURNING user_id,
                  score,
                  tier,
                  last_reviewed_at,
                  updated_at
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setInt(2, score)
        statement.setString(3, tier)
        statement.setBoolean(4, reviewed)
        statement.setBoolean(5, reviewed)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.toAdminRiskActionRecord()
        }
    }

internal fun Connection.updateRiskReviewedAt(userId: UUID): AdminRiskActionRecord? =
    prepareStatement(
        """
        UPDATE account_risk_states
        SET last_reviewed_at = now(),
            updated_at = now()
        WHERE user_id = ?
        RETURNING user_id,
                  score,
                  tier,
                  last_reviewed_at,
                  updated_at
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toAdminRiskActionRecord() else null
        }
    }

internal fun Connection.insertRiskEvent(
    userId: UUID,
    source: String,
    reason: String,
    delta: Int,
    state: AdminRiskActionRecord,
    metadataJson: String,
) {
    prepareStatement(
        """
        INSERT INTO account_risk_events (
            id,
            user_id,
            actor_user_id,
            source,
            reason,
            delta,
            score_after,
            tier_after,
            metadata
        )
        VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?::jsonb)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, userId)
        statement.setString(3, source)
        statement.setString(4, reason)
        statement.setInt(5, delta)
        statement.setInt(6, state.score)
        statement.setString(7, state.tier)
        statement.setString(8, metadataJson)
        statement.executeUpdate()
    }
}
