package com.pynapli.server.psync

import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlinx.serialization.json.Json
import org.postgresql.util.PGobject
import java.sql.ResultSet
import java.util.UUID

/**
 * Shared JDBC plumbing for [JdbcPsyncSessionRepository], extracted to keep the repository class
 * focused on the [PsyncSessionRepository] interface methods. Holds the transaction wrapper, the
 * `jsonb` encoder, and the `ResultSet` row-mappers. `json` is passed in (the repository owns the
 * instance) so these stay free functions. Behavior matches the original private members verbatim.
 */

internal fun jsonbOf(
    json: Json,
    filters: SwipeDeckRequestDto,
): PGobject =
    PGobject().apply {
        type = "jsonb"
        value = json.encodeToString(SwipeDeckRequestDto.serializer(), filters)
    }

internal fun ResultSet.toSession(json: Json): PsyncSession =
    PsyncSession(
        id = getObject("id", UUID::class.java),
        partnerLinkId = getObject("partner_link_id", UUID::class.java),
        initiatorUserId = getObject("initiator_user_id", UUID::class.java),
        status = getString("status"),
        filters = json.decodeFromString(SwipeDeckRequestDto.serializer(), getString("filters")),
        deckSize = getInt("deck_size"),
        deckSessionId = getObject("deck_session_id", UUID::class.java),
        createdAt = getTimestamp("created_at").toInstant(),
        startedAt = getTimestamp("started_at")?.toInstant(),
        expiresAt = getTimestamp("expires_at").toInstant(),
        endedAt = getTimestamp("ended_at")?.toInstant(),
        participants = emptyList(),
    )

internal fun ResultSet.toDeckCard(json: Json): PsyncSessionCard =
    PsyncSessionCard(
        id = getObject("id", UUID::class.java),
        sessionId = getObject("session_id", UUID::class.java),
        position = getInt("position"),
        targetUserId = getObject("target_user_id", UUID::class.java),
        cardTokenHash = getString("card_token_hash"),
        snapshot = json.decodeFromString(SwipeCardDto.serializer(), getString("card_snapshot")),
        dealtAt = getTimestamp("dealt_at").toInstant(),
    )

internal fun ResultSet.toAction(): PsyncSessionAction =
    PsyncSessionAction(
        actorUserId = getObject("actor_user_id", UUID::class.java),
        targetUserId = getObject("target_user_id", UUID::class.java),
        action = getString("action"),
        createdAt = getTimestamp("created_at").toInstant(),
    )

internal fun ResultSet.toParticipant(): PsyncSessionParticipant =
    PsyncSessionParticipant(
        userId = getObject("user_id", UUID::class.java),
        role = getString("role"),
        consentStatus = getString("consent_status"),
        approved = getBoolean("approved"),
        started = getBoolean("started"),
        displayName = getString("display_name"),
    )
