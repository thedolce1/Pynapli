package com.pynapli.app.verification

import androidx.compose.runtime.Immutable
import com.pynapli.app.media.PickedMediaForUpload
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.PhotoVerificationStatus
import com.pynapli.shared.network.dto.PhotoVerificationStatusDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Immutable
data class PhotoVerificationUiState(
    val sheetVisible: Boolean = false,
    val loaded: Boolean = false,
    val isLoading: Boolean = false,
    val isRequestingChallenge: Boolean = false,
    val isSubmitting: Boolean = false,
    /** One of [PhotoVerificationStatus]. */
    val status: String = PhotoVerificationStatus.NONE,
    val challengeId: String? = null,
    val poseInstruction: String? = null,
    val challengeExpiresAt: String? = null,
    val rejectionReason: String? = null,
    val retryAvailableAt: String? = null,
    val errorMessage: String? = null,
) {
    val isVerified: Boolean get() = status == PhotoVerificationStatus.VERIFIED

    /** The server enforces any cooldown; the client only stops a double tap. */
    val canRequestChallenge: Boolean
        get() = (status == PhotoVerificationStatus.NONE || status == PhotoVerificationStatus.REJECTED) && !isRequestingChallenge

    val canTakeSelfie: Boolean get() = status == PhotoVerificationStatus.CHALLENGE && challengeId != null && !isSubmitting
}

/**
 * Profile Hub "Get verified": loads the server status, asks for a pose only on an explicit tap, and
 * sends one selfie. A failed submit is never retried (it is not idempotent, and a lost response may
 * already have queued it): the status is refetched instead, which is canonical.
 */
class PhotoVerificationStateHolder(
    private val repository: PhotoVerificationRepository,
    private val nowMillis: () -> Long = ::currentMillis,
) {
    private val _state = MutableStateFlow(PhotoVerificationUiState())
    val state: StateFlow<PhotoVerificationUiState> = _state.asStateFlow()

    fun open() {
        _state.update { it.copy(sheetVisible = true, errorMessage = null) }
    }

    fun dismiss() {
        _state.update { it.copy(sheetVisible = false, errorMessage = null) }
    }

    suspend fun load() {
        _state.update { it.copy(isLoading = true) }
        when (val result = repository.loadStatus()) {
            is AppResult.Success -> _state.update { it.applying(result.value).copy(isLoading = false, loaded = true) }
            is AppResult.Failure -> _state.update { it.copy(isLoading = false, errorMessage = result.error.message) }
        }
    }

    suspend fun requestChallenge() {
        if (state.value.isRequestingChallenge) return
        _state.update { it.copy(isRequestingChallenge = true, errorMessage = null) }
        when (val result = repository.requestChallenge()) {
            is AppResult.Success -> _state.update { it.applying(result.value).copy(isRequestingChallenge = false, loaded = true) }
            is AppResult.Failure -> _state.update { it.copy(isRequestingChallenge = false, errorMessage = result.error.message) }
        }
    }

    /** Delivered by the platform photo picker (PickedMediaRouter). */
    suspend fun submitSelfie(media: PickedMediaForUpload) {
        val current = state.value
        if (current.isSubmitting) return
        val challengeId = current.challengeId
        if (current.status != PhotoVerificationStatus.CHALLENGE || challengeId == null) {
            showError("Get your pose first, then take the selfie.")
            return
        }
        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        when (val result = repository.submitSelfie(challengeId, media)) {
            is AppResult.Success -> _state.update { it.applying(result.value).copy(isSubmitting = false) }
            is AppResult.Failure -> {
                _state.update { it.copy(isSubmitting = false) }
                load()
                _state.update { it.copy(errorMessage = result.error.message) }
            }
        }
    }

    fun showError(message: String) {
        _state.update { it.copy(errorMessage = message) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** Sign-out: never show one account's verification state to the next. */
    fun reset() {
        _state.value = PhotoVerificationUiState()
    }

    fun retryLabel(): String? = photoVerificationRetryLabel(state.value.retryAvailableAt, nowMillis())

    private fun PhotoVerificationUiState.applying(dto: PhotoVerificationStatusDto): PhotoVerificationUiState =
        copy(
            status = dto.status,
            challengeId = dto.challenge?.challengeId,
            poseInstruction = dto.challenge?.instruction,
            challengeExpiresAt = dto.challenge?.expiresAt,
            rejectionReason = dto.rejectionReason,
            retryAvailableAt = dto.retryAvailableAt,
        )
}

/** "You can try again in 25 min." for a rejection's retry time, or null when there is none. */
@OptIn(ExperimentalTime::class)
internal fun photoVerificationRetryLabel(
    retryAvailableAt: String?,
    nowMillis: Long,
): String? {
    val retryMillis = retryAvailableAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: return null
    val minutes = (retryMillis - nowMillis + 59_999) / 60_000
    return when {
        minutes <= 0 -> "You can try again now."
        minutes < 60 -> "You can try again in $minutes min."
        else -> "You can try again in ${(minutes + 59) / 60} h."
    }
}

@OptIn(ExperimentalTime::class)
private fun currentMillis(): Long = Clock.System.now().toEpochMilliseconds()
