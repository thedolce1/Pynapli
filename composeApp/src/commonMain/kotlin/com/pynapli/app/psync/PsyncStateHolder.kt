package com.pynapli.app.psync

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto
import com.pynapli.shared.network.dto.CurrentPsyncSessionDto
import com.pynapli.shared.network.dto.PsyncActionResponseDto
import com.pynapli.shared.network.dto.PsyncDeckResponseDto
import com.pynapli.shared.network.dto.PsyncRecapDto
import com.pynapli.shared.network.dto.PsyncSessionDto
import com.pynapli.shared.network.dto.SubmitPsyncActionRequestDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Client-side seam over the Psync HTTP API (PS-5a). The state holder talks only to this; the
 * Ktor client never reaches a screen. Every call returns an [AppResult] so the state holder can
 * surface failures without throwing.
 */
interface PsyncRepository {
    suspend fun loadCurrentSession(): AppResult<CurrentPsyncSessionDto>

    suspend fun createSession(deckSize: Int): AppResult<PsyncSessionDto>

    suspend fun accept(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun deny(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun leave(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun start(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun loadDeck(sessionId: String): AppResult<PsyncDeckResponseDto>

    suspend fun submitAction(
        sessionId: String,
        cardToken: String,
        action: String,
    ): AppResult<PsyncActionResponseDto>

    suspend fun pause(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun resume(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun complete(sessionId: String): AppResult<PsyncRecapDto>

    suspend fun loadRecap(sessionId: String): AppResult<PsyncRecapDto>

    suspend fun nudge(sessionId: String): AppResult<Unit>
}

class ApiPsyncRepository(
    private val apiClient: PynapliApiClient,
) : PsyncRepository {
    override suspend fun loadCurrentSession() = apiClient.getCurrentPsyncSession()

    override suspend fun createSession(deckSize: Int) = apiClient.createPsyncSession(CreatePsyncSessionRequestDto(deckSize = deckSize))

    override suspend fun accept(sessionId: String) = apiClient.acceptPsyncSession(sessionId)

    override suspend fun deny(sessionId: String) = apiClient.denyPsyncSession(sessionId)

    override suspend fun leave(sessionId: String) = apiClient.leavePsyncSession(sessionId)

    override suspend fun start(sessionId: String) = apiClient.startPsyncSession(sessionId)

    override suspend fun loadDeck(sessionId: String) = apiClient.getPsyncDeck(sessionId)

    override suspend fun submitAction(
        sessionId: String,
        cardToken: String,
        action: String,
    ) = apiClient.submitPsyncAction(sessionId, SubmitPsyncActionRequestDto(cardToken, action))

    override suspend fun pause(sessionId: String) = apiClient.pausePsyncSession(sessionId)

    override suspend fun resume(sessionId: String) = apiClient.resumePsyncSession(sessionId)

    override suspend fun complete(sessionId: String) = apiClient.completePsyncSession(sessionId)

    override suspend fun loadRecap(sessionId: String) = apiClient.getPsyncRecap(sessionId)

    override suspend fun nudge(sessionId: String): AppResult<Unit> =
        when (val result = apiClient.nudgePsyncSession(sessionId)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
}

/**
 * Drives the Psync flow client-side (PS-5a). Holds an immutable [PsyncUiState]; the screens
 * (PS-5b/PS-5c) render it and call these suspend actions. The server stays authoritative — every
 * mutation refetches/maps the server session, and the deck is (re)loaded whenever a session is
 * active so both partners see the same canonical state on reconnect.
 */
class PsyncStateHolder(
    private val repository: PsyncRepository,
    private val viewerUserId: String?,
) {
    private val _state = MutableStateFlow(PsyncUiState())
    val state: StateFlow<PsyncUiState> = _state.asStateFlow()

    /** The current session id, if a session is loaded. */
    private val currentSessionId: String? get() = _state.value.session?.id

    /** First load / pull-to-refresh: the viewer's current session (+ deck if it's live). */
    suspend fun refresh() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        when (val result = repository.loadCurrentSession()) {
            is AppResult.Success -> {
                applySession(result.value.session)
                _state.update { it.copy(isLoading = false) }
                when (_state.value.session?.status) {
                    "active", "paused" -> loadDeck()
                    "completed" -> loadRecap()
                    else -> Unit
                }
            }
            is AppResult.Failure ->
                _state.update { it.copy(isLoading = false, errorMessage = result.error.message) }
        }
    }

    suspend fun createSession(deckSize: Int = DEFAULT_DECK_SIZE) = mutateSession { repository.createSession(deckSize) }

    suspend fun accept() = onSession { mutateSession { repository.accept(it) } }

    suspend fun deny() = onSession { mutateSession { repository.deny(it) } }

    suspend fun leave() = onSession { mutateSession { repository.leave(it) } }

    suspend fun pause() = onSession { mutateSession { repository.pause(it) } }

    suspend fun resume() = onSession { mutateSession { repository.resume(it) } }

    /** Tap Start; once the session goes active, load the shared deck. */
    suspend fun start() =
        onSession { sessionId ->
            mutateSession { repository.start(sessionId) }
            refreshDeckIfActive()
        }

    /** Reload the shared deck for the active session. */
    suspend fun loadDeck() =
        onSession { sessionId ->
            when (val result = repository.loadDeck(sessionId)) {
                is AppResult.Success -> _state.update { it.copy(deck = result.value.cards.map { card -> card.toUi() }) }
                is AppResult.Failure -> _state.update { it.copy(errorMessage = result.error.message) }
            }
        }

    /** Record the viewer's private decision on a card; auto-completion loads the recap. */
    suspend fun submitAction(
        cardToken: String,
        action: String,
    ) = onSession { sessionId ->
        when (val result = repository.submitAction(sessionId, cardToken, action)) {
            is AppResult.Success -> {
                // Reflect the viewer's own choice locally (private — never the partner's).
                _state.update { current ->
                    current.copy(
                        deck = current.deck.map { if (it.cardToken == cardToken) it.copy(viewerAction = action) else it },
                    )
                }
                if (result.value.sessionCompleted) loadRecap()
            }
            is AppResult.Failure -> _state.update { it.copy(errorMessage = result.error.message) }
        }
    }

    suspend fun complete() =
        onSession { sessionId ->
            _state.update { it.copy(isMutating = true, errorMessage = null) }
            when (val result = repository.complete(sessionId)) {
                is AppResult.Success ->
                    _state.update { it.copy(isMutating = false, recap = result.value.toUi()) }
                is AppResult.Failure ->
                    _state.update { it.copy(isMutating = false, errorMessage = result.error.message) }
            }
            refresh()
        }

    suspend fun loadRecap() =
        onSession { sessionId ->
            when (val result = repository.loadRecap(sessionId)) {
                is AppResult.Success -> _state.update { it.copy(recap = result.value.toUi()) }
                is AppResult.Failure -> _state.update { it.copy(errorMessage = result.error.message) }
            }
        }

    /** Nudge the partner. A cooldown rejection surfaces as a (non-fatal) error message. */
    suspend fun nudge() =
        onSession { sessionId ->
            when (val result = repository.nudge(sessionId)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> _state.update { it.copy(errorMessage = result.error.message) }
            }
        }

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    private suspend fun mutateSession(call: suspend () -> AppResult<PsyncSessionDto>) {
        _state.update { it.copy(isMutating = true, errorMessage = null) }
        when (val result = call()) {
            is AppResult.Success -> {
                applySession(result.value)
                _state.update { it.copy(isMutating = false) }
            }
            is AppResult.Failure ->
                _state.update { it.copy(isMutating = false, errorMessage = result.error.message) }
        }
    }

    private suspend fun onSession(block: suspend (String) -> Unit) {
        val sessionId = currentSessionId ?: return
        block(sessionId)
    }

    private suspend fun refreshDeckIfActive() {
        if (_state.value.session?.status == "active") loadDeck()
    }

    private fun applySession(session: PsyncSessionDto?) {
        _state.update { current ->
            if (session == null) {
                current.copy(session = null, deck = emptyList())
            } else {
                current.copy(session = session.toUi(viewerUserId))
            }
        }
    }

    private companion object {
        const val DEFAULT_DECK_SIZE = 25
    }
}
