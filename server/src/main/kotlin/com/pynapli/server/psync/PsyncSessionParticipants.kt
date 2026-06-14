package com.pynapli.server.psync

import java.util.UUID

internal fun PsyncSession.allParticipantIds(): List<UUID> = participants.map { it.userId }

internal fun PsyncSession.otherParticipantIds(userId: UUID): List<UUID> = participants.map { it.userId }.filter { it != userId }

internal fun PsyncSession.partnerIdForInitiator(): UUID? = participants.firstOrNull { it.userId != initiatorUserId }?.userId
