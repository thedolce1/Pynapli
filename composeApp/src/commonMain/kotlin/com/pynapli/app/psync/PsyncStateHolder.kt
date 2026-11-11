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
import com.pynapli.shared.network.dto.SuggestPsyncFiltersRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
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

    /** One session by id, whatever its status (the "current" read only returns a live one). */
    suspend fun loadSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun createSession(
        deckSize: Int,
        filters: SwipeDeckRequestDto,
    ): AppResult<PsyncSessionDto>

    /** Propose different filters. Resets both approvals except the suggester's. */
    suspend fun suggestFilters(
        sessionId: String,
        filters: SwipeDeckRequestDto,
    ): AppResult<PsyncSessionDto>

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

    override suspend fun loadSession(sessionId: String) = apiClient.getPsyncSession(sessionId)

    override suspend fun createSession(
        deckSize: Int,
        filters: SwipeDeckRequestDto,
    ) = apiClient.createPsyncSession(CreatePsyncSessionRequestDto(filters = filters, deckSize = deckSize))

    override suspend fun suggestFilters(
        sessionId: String,
        filters: SwipeDeckRequestDto,
    ) = apiClient.suggestPsyncFilters(sessionId, SuggestPsyncFiltersRequestDto(filters))

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
                val live = result.value.session
                val held = _state.value.session
                when {
                    live != null -> applySession(live)
                    // The server only reports a LIVE session as current. Ours stopped being live
                    // (finished, a partner left, it expired), so ask what became of it rather than
                    // dropping back to the start screen and losing the recap.
                    held != null && held.phase !in PsyncPhase.FINISHED -> followEndedSession(held.id)
                    // Already showing a recap or an ended notice: keep it until the viewer leaves.
                    held != null -> Unit
                    else -> applySession(null)
                }
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

    /** Invite the linked partner to a session over [filters] with a deck of [deckSize]. */
    suspend fun createSession(
        deckSize: Int = DEFAULT_DECK_SIZE,
        filters: SwipeDeckRequestDto = SwipeDeckRequestDto(),
    ) = mutateSession { repository.createSession(deckSize, filters) }

    /** Propose different filters before the deck starts; the partner must approve them again. */
    suspend fun suggestFilters(filters: SwipeDeckRequestDto) = onSession { sessionId -> mutateSession { repository.suggestFilters(sessionId, filters) } }

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

    /**
     * Record the viewer's private decision on a card; auto-completion loads the recap.
     *
     * Optimistic: the card leaves the stack at once so swiping never waits on the network, and
     * comes back if the server refuses the decision (no Pineapple Likes left, session ended).
     * Retrying is safe because the server keeps the first decision per card.
     */
    suspend fun submitAction(
        cardToken: String,
        action: String,
    ) = onSession { sessionId ->
        val previous =
            _state.value.deck
                .firstOrNull { it.cardToken == cardToken }
                ?.viewerAction
        // The viewer's own choice only (private — never the partner's).
        setViewerAction(cardToken, action)
        _state.update { it.copy(errorMessage = null) }
        when (val result = repository.submitAction(sessionId, cardToken, action)) {
            is AppResult.Success -> {
                if (result.value.sessionCompleted) {
                    // That was the last decision of the deck: show the recap.
                    _state.update { current -> current.copy(session = current.session?.copy(status = "completed")) }
                    loadRecap()
                }
            }
            is AppResult.Failure -> {
                setViewerAction(cardToken, previous)
                if (result.error.code in SESSION_GONE_CODES) {
                    // The session ended under us (partner left, link ended, deadline passed) and
                    // the realtime update was missed. Follow it to the recap instead of leaving a
                    // dead deck with an error on it.
                    refresh()
                } else {
                    _state.update { it.copy(errorMessage = result.error.message) }
                }
            }
        }
    }

    private fun setViewerAction(
        cardToken: String,
        action: String?,
    ) = _state.update { current ->
        current.copy(deck = current.deck.map { if (it.cardToken == cardToken) it.copy(viewerAction = action) else it })
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
                // Without this the button did nothing visible.
                is AppResult.Success -> _state.update { it.copy(notice = "Nudge sent.", errorMessage = null) }
                is AppResult.Failure -> _state.update { it.copy(errorMessage = result.error.message) }
            }
        }

    /**
     * A realtime delta arrived for [sessionId]. Deltas only say that something changed, never what
     * was swiped, so most just trigger a refetch of the canonical session.
     */
    suspend fun onRealtimeDelta(
        sessionId: String,
        type: String,
    ) {
        val partner = _state.value.session?.partnerDisplayName ?: "Your partner"
        when (type) {
            // A nudge changes nothing server-side; it only needs to be seen.
            DELTA_NUDGE -> _state.update { it.copy(notice = "$partner is ready when you are.") }
            DELTA_PARTNER_FINISHED -> {
                if (sessionId == currentSessionId) _state.update { it.copy(partnerFinished = true) }
                refresh()
            }
            else -> refresh()
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    /** Leave a finished session behind (recap or ended notice dismissed) so a new one can start. */
    fun dismissFinished() {
        if (_state.value.phase in PsyncPhase.FINISHED) _state.value = PsyncUiState()
    }

    private suspend fun followEndedSession(sessionId: String) {
        when (val result = repository.loadSession(sessionId)) {
            is AppResult.Success -> applySession(result.value)
            // Can't tell what happened to it; fall back to the start screen.
            is AppResult.Failure -> applySession(null)
        }
    }

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
                current.copy(session = null, deck = emptyList(), partnerFinished = false, notice = null)
            } else {
                val sameLiveSession = current.session?.id == session.id && session.status == "active"
                current.copy(
                    session = session.toUi(viewerUserId),
                    // "Partner finished" only means something for the deck it was said about.
                    partnerFinished = current.partnerFinished && sameLiveSession,
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_DECK_SIZE = 25
        const val DELTA_NUDGE = "nudge"
        const val DELTA_PARTNER_FINISHED = "partner_finished"
        val SESSION_GONE_CODES = setOf("PSYNC_INVALID_STATE", "PSYNC_SESSION_NOT_FOUND")
    }
}
