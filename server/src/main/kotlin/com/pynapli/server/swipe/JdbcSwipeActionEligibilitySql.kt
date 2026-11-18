package com.pynapli.server.swipe

import com.pynapli.server.distance.CoarseLocation
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal fun Connection.isSwipeActionTargetEligible(
    viewerUserId: UUID,
    targetUserId: UUID,
    radiusMeters: Int,
): Boolean =
    prepareStatement(
        """
        WITH viewer_location AS (
            -- Travel-mode override for viewer side; target-side travel
            -- must mirror queryDeck so dealt visiting cards remain
            -- actionable.
            SELECT pynapli_location_cell(COALESCE(tl.geom, ul.geom)) AS geom
            FROM user_locations ul
            LEFT JOIN travel_locations tl
              ON tl.user_id = ul.user_id
             AND tl.status = 'active'
             AND tl.active_until IS NULL
            WHERE ul.user_id = ?
        )
        SELECT EXISTS (
            SELECT 1
            FROM viewer_location v
            JOIN user_locations target_location
              ON target_location.user_id = ?
            JOIN users target
              ON target.id = target_location.user_id
            JOIN profiles target_profile
              ON target_profile.user_id = target.id
            JOIN discovery_settings target_settings
              ON target_settings.user_id = target.id
            LEFT JOIN travel_locations target_travel
              ON target_travel.user_id = target.id
             AND target_travel.status = 'active'
             AND target_travel.active_until IS NULL
            LEFT JOIN account_risk_states target_risk
              ON target_risk.user_id = target.id
            WHERE target.id = ?
              AND target.status = 'active'
              AND target.deleted_at IS NULL
              AND target_profile.visibility_status = 'visible'
              AND target_settings.swipe_visible = true
              AND target_settings.incognito = false
              AND (target_travel.id IS NULL OR target_settings.visible_while_traveling = true)
              AND COALESCE(target_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
              -- Index pre-filter padded by the cell slack, then the deck's exact membership: the
              -- effective location's cell within the radius step.
              AND (
                  ST_DWithin(v.geom, target_location.geom, ?)
                  OR (target_travel.geom IS NOT NULL AND ST_DWithin(v.geom, target_travel.geom, ?))
              )
              AND pynapli_coarse_distance_m(v.geom, COALESCE(target_travel.geom, target_location.geom)) <= $radiusMeters
              AND NOT EXISTS (
                  SELECT 1 FROM swipe_actions sa
                  WHERE sa.actor_user_id = ?
                    AND sa.target_user_id = target.id
                    AND sa.rewound_at IS NULL
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM user_hidden_profiles uhp
                  WHERE uhp.hider_user_id = ?
                    AND uhp.hidden_user_id = target.id
              )
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, viewerUserId)
        statement.setObject(2, targetUserId)
        statement.setObject(3, targetUserId)
        statement.setDouble(4, radiusMeters + CoarseLocation.CELL_PAD_METERS)
        statement.setDouble(5, radiusMeters + CoarseLocation.CELL_PAD_METERS)
        statement.setObject(6, viewerUserId)
        statement.setObject(7, viewerUserId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

internal fun Connection.checkSwipeActionPreconditions(
    sessionId: UUID,
    cardTokenHash: String,
    viewerUserId: UUID,
    targetUserId: UUID,
    radiusMeters: Int,
    now: Instant,
): SwipeActionPreconditionCheck =
    prepareStatement(
        """
        WITH viewer_location AS (
            -- Travel-mode override for the action-precondition radius
            -- check; if the viewer Liked someone in their travel city
            -- then jumped home, this query still uses the travel point
            -- so the action remains within-radius for the original deck.
            SELECT pynapli_location_cell(COALESCE(tl.geom, ul.geom)) AS geom,
                   tl.id AS travel_location_id
            FROM user_locations ul
            LEFT JOIN travel_locations tl
              ON tl.user_id = ul.user_id
             AND tl.status = 'active'
             AND tl.active_until IS NULL
            WHERE ul.user_id = ?
        )
        SELECT
            EXISTS (
                SELECT 1
                FROM swipe_deck_sessions s
                JOIN swipe_deck_cards c
                  ON c.deck_session_id = s.id
                WHERE s.id = ?
                  AND s.viewer_user_id = ?
                  AND c.viewer_user_id = ?
                  AND c.target_user_id = ?
                  AND c.card_token_hash = ?
                    AND s.expires_at > ?
                    AND c.action_recorded_at IS NULL
            ) AS deck_card_actionable,
            (
                SELECT travel_location_id
                FROM viewer_location
                LIMIT 1
            ) AS viewer_travel_location_id,
            EXISTS (
                SELECT 1
                FROM viewer_location v
                JOIN user_locations target_location
                  ON target_location.user_id = ?
                JOIN users target
                  ON target.id = target_location.user_id
                JOIN profiles target_profile
                  ON target_profile.user_id = target.id
                JOIN discovery_settings target_settings
                  ON target_settings.user_id = target.id
                LEFT JOIN travel_locations target_travel
                  ON target_travel.user_id = target.id
                 AND target_travel.status = 'active'
                 AND target_travel.active_until IS NULL
                LEFT JOIN account_risk_states target_risk
                  ON target_risk.user_id = target.id
                WHERE target.id = ?
                  AND target.status = 'active'
                  AND target.deleted_at IS NULL
                  AND target_profile.visibility_status = 'visible'
                  AND target_settings.swipe_visible = true
                  AND target_settings.incognito = false
                  AND (target_travel.id IS NULL OR target_settings.visible_while_traveling = true)
                  AND COALESCE(target_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
                  AND (
                      ST_DWithin(v.geom, target_location.geom, ?)
                      OR (target_travel.geom IS NOT NULL AND ST_DWithin(v.geom, target_travel.geom, ?))
                  )
                  AND pynapli_coarse_distance_m(v.geom, COALESCE(target_travel.geom, target_location.geom)) <= $radiusMeters
                  AND NOT EXISTS (
                      SELECT 1 FROM swipe_actions sa
                      WHERE sa.actor_user_id = ?
                        AND sa.target_user_id = target.id
                        AND sa.rewound_at IS NULL
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM user_blocks ub
                      WHERE (ub.blocker_user_id = ? AND ub.blocked_user_id = target.id)
                         OR (ub.blocker_user_id = target.id AND ub.blocked_user_id = ?)
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM user_hidden_profiles uhp
                      WHERE uhp.hider_user_id = ?
                        AND uhp.hidden_user_id = target.id
                  )
            ) AS target_eligible,
            -- "Allow notes with Pineapple Likes": only decides whether a note may ride along.
            COALESCE(
                (SELECT allow_intro_notes FROM discovery_settings WHERE user_id = ?),
                false
            ) AS target_accepts_notes
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, viewerUserId)
        statement.setObject(2, sessionId)
        statement.setObject(3, viewerUserId)
        statement.setObject(4, viewerUserId)
        statement.setObject(5, targetUserId)
        statement.setString(6, cardTokenHash)
        statement.setTimestamp(7, Timestamp.from(now))
        statement.setObject(8, targetUserId)
        statement.setObject(9, targetUserId)
        statement.setDouble(10, radiusMeters + CoarseLocation.CELL_PAD_METERS)
        statement.setDouble(11, radiusMeters + CoarseLocation.CELL_PAD_METERS)
        statement.setObject(12, viewerUserId)
        statement.setObject(13, viewerUserId)
        statement.setObject(14, viewerUserId)
        statement.setObject(15, viewerUserId)
        statement.setObject(16, targetUserId)
        statement.executeQuery().use { rs ->
            rs.next()
            SwipeActionPreconditionCheck(
                deckCardActionable = rs.getBoolean("deck_card_actionable"),
                targetEligible = rs.getBoolean("target_eligible"),
                viewerTravelLocationId = rs.getObject("viewer_travel_location_id", UUID::class.java),
                targetAcceptsNotes = rs.getBoolean("target_accepts_notes"),
            )
        }
    }

internal fun Connection.targetAcceptsPineappleLikeNotes(targetUserId: UUID): Boolean =
    prepareStatement("SELECT allow_intro_notes FROM discovery_settings WHERE user_id = ?").use { statement ->
        statement.setObject(1, targetUserId)
        statement.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
    }
