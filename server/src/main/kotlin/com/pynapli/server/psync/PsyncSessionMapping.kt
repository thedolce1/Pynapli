package com.pynapli.server.psync

import com.pynapli.shared.network.dto.PsyncParticipantDto
import com.pynapli.shared.network.dto.PsyncSessionDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import java.util.UUID

internal fun PsyncSession.toDto(): PsyncSessionDto =
    PsyncSessionDto(
        id = id.toString(),
        partnerLinkId = partnerLinkId.toString(),
        initiatorUserId = initiatorUserId.toString(),
        status = status,
        filters = filters,
        deckSize = deckSize,
        createdAt = createdAt.toString(),
        startedAt = startedAt?.toString(),
        expiresAt = expiresAt.toString(),
        participants = participants.map { it.toDto() },
        locationLabel = locationLabel,
    )

internal fun PsyncSessionParticipant.toDto(): PsyncParticipantDto =
    PsyncParticipantDto(
        userId = userId.toString(),
        displayName = displayName,
        role = role,
        consentStatus = consentStatus,
        approved = approved,
        started = started,
    )

internal fun PsyncSessionDto.participantIds(): List<UUID> = participants.map { UUID.fromString(it.userId) }

/** A compact, human-readable one-line summary of the deck filters for the recap card. */
internal fun SwipeDeckRequestDto.toPsyncFiltersSummary(): String {
    val parts =
        buildList {
            add("${radiusMeters / 1000}km")
            val lo = minimumAge
            val hi = maximumAge
            when {
                lo != null && hi != null -> add("ages $lo-$hi")
                lo != null -> add("ages $lo+")
                hi != null -> add("ages up to $hi")
            }
            if (verifiedOnly) add("verified only")
            if (discoveryStyles.isNotEmpty()) add(discoveryStyles.joinToString("/"))
            if (intents.isNotEmpty()) add(intents.joinToString("/"))
            val tagCount = lifestyleTags.size + logisticsTags.size
            if (tagCount > 0) add("$tagCount tags")
        }
    return parts.joinToString(" · ")
}
