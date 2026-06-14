package com.pynapli.server.psync

import com.pynapli.shared.network.dto.PsyncSessionDto

internal fun PsyncSessionRepository.MutateOutcome.toDtoOrThrow(): PsyncSessionDto =
    when (this) {
        is PsyncSessionRepository.MutateOutcome.Updated -> session.toDto()
        PsyncSessionRepository.MutateOutcome.SessionNotFound ->
            throw PsyncException("PSYNC_SESSION_NOT_FOUND")
        PsyncSessionRepository.MutateOutcome.NotParticipant ->
            throw PsyncException("PSYNC_NOT_PARTICIPANT")
        PsyncSessionRepository.MutateOutcome.InvalidState ->
            throw PsyncException("PSYNC_INVALID_STATE")
    }

internal fun PsyncSessionRepository.StartOutcome.updatedOrThrow(): PsyncSessionRepository.StartOutcome.Updated =
    when (this) {
        is PsyncSessionRepository.StartOutcome.Updated -> this
        PsyncSessionRepository.StartOutcome.SessionNotFound -> throw PsyncException("PSYNC_SESSION_NOT_FOUND")
        PsyncSessionRepository.StartOutcome.NotParticipant -> throw PsyncException("PSYNC_NOT_PARTICIPANT")
        PsyncSessionRepository.StartOutcome.InvalidState -> throw PsyncException("PSYNC_INVALID_STATE")
    }

internal fun PsyncSessionRepository.CompleteOutcome.completedOrThrow(): PsyncSessionRepository.CompleteOutcome.Completed =
    when (this) {
        is PsyncSessionRepository.CompleteOutcome.Completed -> this
        PsyncSessionRepository.CompleteOutcome.SessionNotFound -> throw PsyncException("PSYNC_SESSION_NOT_FOUND")
        PsyncSessionRepository.CompleteOutcome.NotParticipant -> throw PsyncException("PSYNC_NOT_PARTICIPANT")
        PsyncSessionRepository.CompleteOutcome.InvalidState -> throw PsyncException("PSYNC_INVALID_STATE")
    }
