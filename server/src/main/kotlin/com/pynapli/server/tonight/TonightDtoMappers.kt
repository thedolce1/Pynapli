package com.pynapli.server.tonight

import com.pynapli.shared.network.dto.TonightSessionDto
import com.pynapli.shared.network.dto.TonightSessionParticipantDto

internal fun TonightSession.toDto(): TonightSessionDto =
    TonightSessionDto(
        id = id.toString(),
        mode = mode,
        intent = intent,
        hostMode = hostMode,
        note = note,
        photoMediaAssetId = photoMediaAssetId?.toString(),
        mapVisible = mapVisible,
        status = status,
        createdAt = createdAt.toString(),
        startedAt = startedAt?.toString(),
        expiresAt = expiresAt.toString(),
        participants = participants.map { it.toDto() },
    )

private fun TonightSessionParticipant.toDto(): TonightSessionParticipantDto =
    TonightSessionParticipantDto(
        userId = userId.toString(),
        displayName = displayName,
        role = role,
        approvalStatus = approvalStatus,
    )
