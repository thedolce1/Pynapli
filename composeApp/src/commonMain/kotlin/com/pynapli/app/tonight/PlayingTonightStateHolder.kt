package com.pynapli.app.tonight

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto
import com.pynapli.shared.network.dto.TonightFeedItemDto
import com.pynapli.shared.network.dto.TonightSessionDto
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * State holder for the Playing Tonight map surface (PT-4). Reads the public feed + the viewer's
 * current session through [TonightRepository] and exposes immutable [PlayingTonightUiState];
 * DTOs never leak into the screen. Server stays the product authority — this only renders what
 * the feed allows (zone-only location, individual rows). Proposing/approving sessions is a later
 * slice and is not wired here.
 */
class PlayingTonightStateHolder(
    private val repository: TonightRepository,
    private val radiusMeters: Int = DEFAULT_RADIUS_METERS,
) {
    private val _state = MutableStateFlow(PlayingTonightUiState())
    val state: StateFlow<PlayingTonightUiState> = _state.asStateFlow()

    /** Parallel first load: the public feed and the viewer's own session at once. */
    suspend fun load() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        val intent = _state.value.intentFilter
        coroutineScope {
            val feedDeferred = async { repository.loadFeed(radiusMeters, intent?.serverValue) }
            val sessionDeferred = async { repository.loadCurrentSession() }
            val photosDeferred = async { repository.loadProfilePhotos() }
            val feedResult = feedDeferred.await()
            val sessionResult = sessionDeferred.await()
            val photosResult = photosDeferred.await()

            _state.update { current ->
                current.copy(
                    isLoading = false,
                    feedItems =
                        when (feedResult) {
                            is AppResult.Success -> feedResult.value
                            is AppResult.Failure -> current.feedItems
                        },
                    currentSession =
                        when (sessionResult) {
                            is AppResult.Success -> sessionResult.value
                            is AppResult.Failure -> current.currentSession
                        },
                    // Best-effort: a photo-list failure shouldn't surface an error or block the surface.
                    photoOptions =
                        when (photosResult) {
                            is AppResult.Success -> photosResult.value
                            is AppResult.Failure -> current.photoOptions
                        },
                    errorMessage = (feedResult as? AppResult.Failure)?.error?.message,
                )
            }
        }
    }

    /** Re-query the feed for the chosen intent (null = All). Leaves the current session untouched. */
    suspend fun setIntentFilter(intent: TonightIntent?) {
        if (_state.value.intentFilter == intent) return
        _state.update { it.copy(intentFilter = intent, isLoading = true, errorMessage = null) }
        when (val result = repository.loadFeed(radiusMeters, intent?.serverValue)) {
            is AppResult.Success ->
                _state.update { it.copy(isLoading = false, feedItems = result.value, errorMessage = null) }

            is AppResult.Failure ->
                _state.update { it.copy(isLoading = false, errorMessage = result.error.message) }
        }
    }

    suspend fun refresh() = load()

    /** Set by the screen while it is showing, so a resume refetch only runs when someone is looking. */
    var isOnScreen: Boolean = false

    /** A quiet refetch of the feed and the viewer's own session: no spinner, keeps content on failure. */
    suspend fun refreshSilently() {
        val intent = _state.value.intentFilter
        coroutineScope {
            val feedDeferred = async { repository.loadFeed(radiusMeters, intent?.serverValue) }
            val sessionDeferred = async { repository.loadCurrentSession() }
            val feedResult = feedDeferred.await()
            val sessionResult = sessionDeferred.await()
            // A filter change while this was in flight wins: its own load already set the feed.
            if (_state.value.intentFilter != intent) return@coroutineScope
            _state.update { current ->
                current.copy(
                    feedItems = (feedResult as? AppResult.Success)?.value ?: current.feedItems,
                    currentSession = (sessionResult as? AppResult.Success)?.value ?: current.currentSession,
                )
            }
        }
    }

    suspend fun refreshIfOnScreen() {
        if (isOnScreen) refreshSilently()
    }

    /** End the viewer's live session; clears the floater on success. Never gated (spec). */
    suspend fun endCurrentSession() {
        val sessionId = _state.value.currentSession?.id ?: return
        when (val result = repository.endSession(sessionId)) {
            is AppResult.Success ->
                _state.update { it.copy(currentSession = null) }

            is AppResult.Failure ->
                _state.update { it.copy(errorMessage = result.error.message) }
        }
    }

    /**
     * Propose a session. Solo goes live immediately; linked-together starts `proposed` and waits
     * for the partner. Either way the result becomes the viewer's current session (drives the floater).
     */
    suspend fun propose(request: ProposeTonightSessionRequestDto) {
        _state.update { it.copy(isMutating = true, errorMessage = null) }
        when (val result = repository.propose(request)) {
            is AppResult.Success ->
                _state.update { it.copy(isMutating = false, currentSession = result.value) }

            is AppResult.Failure -> {
                // Surface the free-tier weekly cap as a clear upgrade prompt (server is the
                // entitlement authority; ending/declining are never gated — PLAYING_TONIGHT_SPEC).
                val message =
                    if (result.error.code == "TONIGHT_WEEKLY_LIMIT_REACHED") {
                        "You've used your free Playing Tonight sessions this week. Upgrade for unlimited sessions."
                    } else {
                        result.error.message
                    }
                _state.update { it.copy(isMutating = false, errorMessage = message) }
            }
        }
    }

    /** The pending partner approves the proposed session; on success it becomes the live session. */
    suspend fun approveSession() {
        val sessionId = _state.value.currentSession?.id ?: return
        _state.update { it.copy(isMutating = true, errorMessage = null) }
        when (val result = repository.approve(sessionId)) {
            is AppResult.Success ->
                _state.update { it.copy(isMutating = false, currentSession = result.value) }

            is AppResult.Failure ->
                _state.update { it.copy(isMutating = false, errorMessage = result.error.message) }
        }
    }

    /** The pending partner declines; no public signal. Clears the floater. */
    suspend fun declineSession() {
        val sessionId = _state.value.currentSession?.id ?: return
        _state.update { it.copy(isMutating = true, errorMessage = null) }
        when (val result = repository.decline(sessionId)) {
            is AppResult.Success ->
                _state.update { it.copy(isMutating = false, currentSession = null) }

            is AppResult.Failure ->
                _state.update { it.copy(isMutating = false, errorMessage = result.error.message) }
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    /**
     * Re-read only the viewer's own session, quietly. Playing Tonight has no realtime channel, so
     * while a together session is waiting on the partner or live with them, the screen calls this
     * on a timer to notice an approval, a decline or the partner ending it.
     */
    suspend fun refreshSession() {
        val before = _state.value.currentSession ?: return
        val result = repository.loadCurrentSession()
        if (result !is AppResult.Success) return
        val after = result.value
        val notice =
            when {
                after != null && before.isProposed && after.status == "active" -> "Your partner joined. You're live together."
                after == null && before.isProposed -> "Your partner can't join tonight."
                after == null && before.status == "active" && before.mode == "together" -> "Tonight's session has ended."
                else -> null
            }
        _state.update { it.copy(currentSession = after, notice = notice ?: it.notice) }
    }

    /** A together session where the next move may be the partner's: worth re-checking. */
    fun shouldWatchPartner(viewerUserId: String?): Boolean {
        val session = _state.value.currentSession ?: return false
        if (session.mode != "together") return false
        return (session.isProposed && !session.pendingApprovalFor(viewerUserId)) || session.status == "active"
    }

    companion object {
        const val DEFAULT_RADIUS_METERS = 50_000
    }
}

/** Repository seam so tests can use a fake without the Ktor client. */
interface TonightRepository {
    suspend fun loadFeed(
        radiusMeters: Int,
        intent: String?,
    ): AppResult<List<TonightFeedItemUi>>

    suspend fun loadCurrentSession(): AppResult<TonightSessionUi?>

    /** The viewer's approved profile photos, offered as optional session photos. */
    suspend fun loadProfilePhotos(): AppResult<List<TonightPhotoOptionUi>>

    suspend fun propose(request: ProposeTonightSessionRequestDto): AppResult<TonightSessionUi>

    suspend fun approve(sessionId: String): AppResult<TonightSessionUi>

    suspend fun decline(sessionId: String): AppResult<TonightSessionUi>

    suspend fun endSession(sessionId: String): AppResult<TonightSessionUi>
}

class ApiTonightRepository(
    private val apiClient: PynapliApiClient,
) : TonightRepository {
    override suspend fun loadFeed(
        radiusMeters: Int,
        intent: String?,
    ): AppResult<List<TonightFeedItemUi>> =
        when (val result = apiClient.getTonightFeed(radiusMeters = radiusMeters, intent = intent)) {
            is AppResult.Success -> AppResult.Success(result.value.items.map { it.toUi() })
            is AppResult.Failure -> AppResult.Failure(result.error)
        }

    override suspend fun loadCurrentSession(): AppResult<TonightSessionUi?> =
        when (val result = apiClient.getCurrentTonightSession()) {
            is AppResult.Success -> AppResult.Success(result.value.session?.toUi())
            is AppResult.Failure -> AppResult.Failure(result.error)
        }

    override suspend fun loadProfilePhotos(): AppResult<List<TonightPhotoOptionUi>> =
        when (val result = apiClient.listMediaAlbum("profile")) {
            is AppResult.Success ->
                AppResult.Success(
                    result.value.items
                        .map { it.mediaAsset }
                        .filter { it.processingStatus == "ready" && it.moderationStatus == "approved" }
                        .map { asset ->
                            TonightPhotoOptionUi(
                                assetId = asset.mediaAssetId,
                                thumbnailUrl = asset.contentUrl ?: apiClient.mediaAssetContentUrl(asset.mediaAssetId),
                            )
                        },
                )
            is AppResult.Failure -> AppResult.Failure(result.error)
        }

    override suspend fun propose(request: ProposeTonightSessionRequestDto): AppResult<TonightSessionUi> =
        when (val result = apiClient.proposeTonightSession(request)) {
            is AppResult.Success -> AppResult.Success(result.value.toUi())
            is AppResult.Failure -> AppResult.Failure(result.error)
        }

    override suspend fun approve(sessionId: String): AppResult<TonightSessionUi> =
        when (val result = apiClient.approveTonightSession(sessionId)) {
            is AppResult.Success -> AppResult.Success(result.value.toUi())
            is AppResult.Failure -> AppResult.Failure(result.error)
        }

    override suspend fun decline(sessionId: String): AppResult<TonightSessionUi> =
        when (val result = apiClient.declineTonightSession(sessionId)) {
            is AppResult.Success -> AppResult.Success(result.value.toUi())
            is AppResult.Failure -> AppResult.Failure(result.error)
        }

    override suspend fun endSession(sessionId: String): AppResult<TonightSessionUi> =
        when (val result = apiClient.endTonightSession(sessionId)) {
            is AppResult.Success -> AppResult.Success(result.value.toUi())
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
}

internal fun TonightFeedItemDto.toUi(): TonightFeedItemUi =
    TonightFeedItemUi(
        sessionId = sessionId,
        userId = person.userId,
        displayName = person.displayName,
        thumbnailUrl = person.thumbnailUrl,
        online = person.online,
        intent = TonightIntent.fromServer(intent),
        intentRaw = intent,
        hostMode = hostMode,
        note = note,
        photoUrl = photoUrl,
        distanceLabel = person.distanceLabel,
        distanceMeters = person.distanceMeters,
        zoneKey = person.zoneKey,
        linkedPartner =
            linkedPartner?.let {
                TonightLinkedPartnerUi(
                    userId = it.userId,
                    displayName = it.displayName,
                    thumbnailUrl = it.thumbnailUrl,
                    online = it.online,
                )
            },
    )

internal fun TonightSessionDto.toUi(): TonightSessionUi =
    TonightSessionUi(
        id = id,
        mode = mode,
        intent = TonightIntent.fromServer(intent),
        intentRaw = intent,
        hostMode = hostMode,
        status = status,
        expiresAt = expiresAt,
        participants =
            participants.map {
                TonightParticipantUi(
                    userId = it.userId,
                    displayName = it.displayName,
                    role = it.role,
                    approvalStatus = it.approvalStatus,
                )
            },
    )
