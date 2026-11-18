package com.pynapli.app.match

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.map
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.MatchCelebrationDto
import com.pynapli.shared.network.dto.MatchDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One full-screen "It's a match" moment. A Psync together match covers two canonical matches. */
@Immutable
data class MatchCelebrationUi(
    val matchIds: List<String>,
    val matchedUserId: String,
    val matchedDisplayName: String?,
    val matchedPhotoUrl: String?,
    val viewerPhotoUrl: String?,
    val pairUserId: String? = null,
    val pairDisplayName: String? = null,
    val pairPhotoUrl: String? = null,
    val createdAt: String = "",
) {
    val primaryMatchId: String get() = matchIds.first()
    val isPair: Boolean get() = pairUserId != null

    /** What "Send a message" opens: the conversation with the matched person. */
    fun toMatchDto(): MatchDto =
        MatchDto(
            matchId = primaryMatchId,
            matchedUserId = matchedUserId,
            matchedDisplayName = matchedDisplayName,
            matchedPhotoUrl = matchedPhotoUrl,
            createdAt = createdAt,
        )
}

@Immutable
data class MatchCelebrationsUi(
    val items: List<MatchCelebrationUi>,
    val viewerPhotoUrl: String?,
)

interface MatchCelebrationRepository {
    suspend fun loadUnseen(): AppResult<MatchCelebrationsUi>

    /** Idempotent on the server, so a retry after a dropped response is safe. */
    suspend fun markSeen(matchIds: List<String>): AppResult<Unit>
}

class ApiMatchCelebrationRepository(
    private val apiClient: PynapliApiClient,
) : MatchCelebrationRepository {
    override suspend fun loadUnseen(): AppResult<MatchCelebrationsUi> =
        apiClient.getMatchCelebrations().map { response ->
            MatchCelebrationsUi(
                items = response.items.map { it.toUi() },
                viewerPhotoUrl = response.viewerPhotoUrl ?: response.items.firstNotNullOfOrNull { it.viewerPhotoUrl },
            )
        }

    override suspend fun markSeen(matchIds: List<String>): AppResult<Unit> = apiClient.markMatchesCelebrated(matchIds).map { }
}

internal fun MatchCelebrationDto.toUi(): MatchCelebrationUi =
    MatchCelebrationUi(
        matchIds = listOfNotNull(matchId, pairMatchId),
        matchedUserId = matchedUserId,
        matchedDisplayName = matchedDisplayName,
        matchedPhotoUrl = matchedPhotoUrl,
        viewerPhotoUrl = viewerPhotoUrl,
        pairUserId = pairUserId,
        pairDisplayName = pairDisplayName,
        pairPhotoUrl = pairPhotoUrl,
        createdAt = createdAt,
    )

/** [current] is the moment on screen; [queued] wait behind it. */
@Immutable
data class MatchCelebrationUiState(
    val current: MatchCelebrationUi? = null,
    val queued: List<MatchCelebrationUi> = emptyList(),
    val viewerPhotoUrl: String? = null,
    // Every match id shown or queued in this process: the swiper also gets the match delta.
    val handledMatchIds: Set<String> = emptySet(),
)

/**
 * Owns the app-wide "It's a match" moment for both people in a match (SWIPE_IMPLEMENTATION_SPEC
 * "Match moment"). The server decides what is unseen (V108), so each person sees each match once
 * across devices:
 *
 * - the swiper gets it straight from the swipe response ([celebrate]);
 * - the other person gets it from [refresh], run on sign-in, on foreground, on the activity
 *   socket's `match` delta and after the socket reconnects (HTTP is canonical; the delta only says
 *   "refetch").
 *
 * The screen calls [markShown] once a moment is actually on screen, which marks it seen on the
 * server; a moment that never got shown (app killed first) comes back next time. State changes are
 * atomic [MutableStateFlow.update]s, so the swipe flush, the socket and the screen can all call in.
 */
class MatchCelebrationStateHolder(
    private val repository: MatchCelebrationRepository,
) {
    private val _state = MutableStateFlow(MatchCelebrationUiState())
    val state: StateFlow<MatchCelebrationUiState> = _state.asStateFlow()

    // Shown, not yet confirmed seen by the server (a failed mark is retried on the next refresh).
    private val unacked = MutableStateFlow<Set<String>>(emptySet())

    suspend fun refresh() {
        flushAcks()
        val result = repository.loadUnseen()
        if (result is AppResult.Success) {
            _state.update { state ->
                result.value.items
                    .fold(state.copy(viewerPhotoUrl = result.value.viewerPhotoUrl ?: state.viewerPhotoUrl)) { acc, item -> acc.enqueue(item) }
                    .advanced()
            }
        }
    }

    /** The swiper's own new match, from the swipe response. */
    fun celebrate(match: MatchDto) {
        _state.update { state ->
            state
                .enqueue(
                    MatchCelebrationUi(
                        matchIds = listOf(match.matchId),
                        matchedUserId = match.matchedUserId,
                        matchedDisplayName = match.matchedDisplayName,
                        matchedPhotoUrl = match.matchedPhotoUrl,
                        viewerPhotoUrl = state.viewerPhotoUrl,
                        createdAt = match.createdAt,
                    ),
                ).advanced()
        }
    }

    /** The current moment is on screen (or was suppressed by settings): mark it seen everywhere. */
    suspend fun markShown(celebration: MatchCelebrationUi) {
        unacked.update { it + celebration.matchIds }
        flushAcks()
    }

    /** "Keep swiping", "Send a message", Esc or back: close it and show the next one, if any. */
    fun dismiss() {
        _state.update { it.copy(current = null).advanced() }
    }

    /** Sign-out: nothing from one account may show for the next. */
    fun reset() {
        _state.value = MatchCelebrationUiState()
        unacked.value = emptySet()
    }

    private suspend fun flushAcks() {
        val ids = unacked.value.toList()
        if (ids.isEmpty()) return
        // The server ignores repeats, so a retry after a lost response is harmless.
        if (repository.markSeen(ids) is AppResult.Success) {
            unacked.update { it - ids.toSet() }
        }
    }
}

private fun MatchCelebrationUiState.enqueue(item: MatchCelebrationUi): MatchCelebrationUiState {
    if (item.matchIds.any { it in handledMatchIds }) return this
    val withPhoto = if (item.viewerPhotoUrl == null) item.copy(viewerPhotoUrl = viewerPhotoUrl) else item
    return copy(queued = queued + withPhoto, handledMatchIds = handledMatchIds + item.matchIds)
}

private fun MatchCelebrationUiState.advanced(): MatchCelebrationUiState {
    if (current != null || queued.isEmpty()) return this
    return copy(current = queued.first(), queued = queued.drop(1))
}
