package com.pynapli.app.onboarding

import androidx.compose.runtime.Immutable
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import com.pynapli.shared.settings.OnboardingProgressStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Drives the DOB-first signup stepper (ONBOARDING_AUTH_SPEC, founder ordering
 * decision 2026-06-09: DOB wheel before account creation, Grindr-style, so an
 * underage visitor is stopped before any account data is collected).
 *
 * Steps: Dob -> (UnderageStop) -> Account(+legal) -> Location -> Name -> Photo -> grid.
 * Everything after Account is skippable — "do it all later" lands on the grid in
 * seconds. Sign-in mode bypasses the stepper entirely.
 *
 * Resume (spec "app closed mid-signup"): signup flips the device-local
 * [OnboardingProgressStore] `completed` flag false (pinned to the signing-up
 * account); [resumeIfNeeded] re-enters the post-auth steps on next launch at the
 * first incomplete one (blank display name -> Name, otherwise Photo) until the
 * user finishes or skips to the grid, which flips the flag back. Plain sign-ins
 * never see the stepper: the flag defaults true and only signup clears it.
 */
class OnboardingStateHolder(
    private val authStateHolder: AuthStateHolder,
    private val profileRepository: OnboardingProfileRepository,
    private val progressStore: OnboardingProgressStore,
    private val todayProvider: () -> LocalDate = ::systemToday,
) {
    private val _state = MutableStateFlow(OnboardingUiState(dob = defaultDob(todayProvider())))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    // Per-process guard so a nudge impression counts one settings session, not one
    // per recomposition of the banner.
    private var photoNudgeCountedThisSession = false

    /** The DOB wheel bounds its year range from the same clock that gates the 18+ check. */
    fun today(): LocalDate = todayProvider()

    fun startCreateAccount() {
        _state.update {
            it.copy(
                active = true,
                step = OnboardingStep.Dob,
                errorMessage = null,
            )
        }
    }

    fun cancelToSignIn() {
        _state.update { it.copy(active = false, step = OnboardingStep.Dob, errorMessage = null) }
    }

    fun updateDob(value: LocalDate) {
        _state.update { it.copy(dob = value, errorMessage = null) }
    }

    fun confirmDob() {
        val current = _state.value
        val nextStep =
            if (current.dob.isAtLeastYearsOld(MINIMUM_AGE_YEARS, todayProvider())) {
                OnboardingStep.Account
            } else {
                OnboardingStep.UnderageStop
            }
        _state.update { it.copy(step = nextStep, errorMessage = null) }
    }

    fun backToDob() {
        _state.update { it.copy(step = OnboardingStep.Dob, errorMessage = null) }
    }

    fun updateEmail(value: String) {
        _state.update { it.copy(email = value, errorMessage = null) }
    }

    fun updatePassword(value: String) {
        _state.update { it.copy(password = value, errorMessage = null) }
    }

    fun updateAcceptedTerms(value: Boolean) {
        _state.update { it.copy(acceptedTerms = value, errorMessage = null) }
    }

    fun updateAcceptedPrivacy(value: Boolean) {
        _state.update { it.copy(acceptedPrivacy = value, errorMessage = null) }
    }

    fun updateDisplayName(value: String) {
        _state.update { it.copy(displayName = value, errorMessage = null) }
    }

    /** Creates the account; on success enters the skippable post-auth steps. */
    suspend fun submitAccount() {
        val current = _state.value
        if (current.isSubmitting) return
        val email = current.email.trim()
        if (!email.contains("@")) {
            _state.update { it.copy(errorMessage = "Enter a valid email address.") }
            return
        }
        if (current.password.length < MIN_PASSWORD_LENGTH) {
            _state.update { it.copy(errorMessage = "Password must be at least $MIN_PASSWORD_LENGTH characters.") }
            return
        }
        if (!current.acceptedTerms || !current.acceptedPrivacy) {
            _state.update { it.copy(errorMessage = "Accept the Terms and Privacy Policy to continue.") }
            return
        }

        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        val created =
            authStateHolder.signupWithDetails(
                email = email,
                password = current.password,
                birthdate = current.dob.toString(),
                // The wheel already gated 18+; passing it explicitly keeps the
                // server contract's explicit-adult-confirmation semantics.
                confirmedAdult = true,
                acceptedTerms = current.acceptedTerms,
                acceptedPrivacy = current.acceptedPrivacy,
            )
        if (created) {
            markOnboardingInProgress(
                authStateHolder.state.value.session
                    ?.userId,
            )
            _state.update {
                it.copy(
                    isSubmitting = false,
                    password = "",
                    step = OnboardingStep.Location,
                )
            }
        } else {
            _state.update {
                it.copy(
                    isSubmitting = false,
                    errorMessage = authStateHolder.state.value.errorMessage,
                )
            }
        }
    }

    /** Location step exits the same way whether the OS prompt was accepted or skipped. */
    fun continueFromLocation() {
        _state.update { it.copy(step = OnboardingStep.Name, errorMessage = null) }
    }

    suspend fun saveDisplayName() {
        val current = _state.value
        if (current.isSubmitting) return
        val name = current.displayName.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(errorMessage = "Enter a display name, or skip for now.") }
            return
        }
        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        when (val result = profileRepository.updateDisplayName(name)) {
            is AppResult.Success ->
                _state.update {
                    it.copy(isSubmitting = false, displayName = name, step = OnboardingStep.Photo)
                }

            is AppResult.Failure ->
                _state.update {
                    it.copy(isSubmitting = false, errorMessage = result.error.message)
                }
        }
    }

    fun skipDisplayName() {
        _state.update { it.copy(step = OnboardingStep.Photo, errorMessage = null) }
    }

    /** Finishes the stepper (photo uploaded or skipped) and releases the user to the grid. */
    suspend fun finish() {
        markOnboardingCompleted()
        _state.update { it.copy(active = false, errorMessage = null) }
    }

    /**
     * Re-enters the post-auth steps after a mid-signup app close. No-op for
     * completed onboarding (the default), unauthenticated sessions, or when the
     * profile already has a display name and the user previously reached Photo.
     */
    suspend fun resumeIfNeeded() {
        if (_state.value.active) return
        val sessionUserId =
            authStateHolder.state.value.session
                ?.userId ?: return
        val progress = progressStore.load()
        if (progress.completed) return
        // The flag is device-local: only the account that started signup resumes.
        // Anyone else signing in on this device skips straight to the grid.
        if (progress.userId != sessionUserId) return

        val me = profileRepository.getMe()
        val displayName =
            when (me) {
                is AppResult.Success ->
                    me.value.profile.displayName
                        .orEmpty()
                // Offline resume: fall back to the first post-auth step; saving
                // the name again is harmless.
                is AppResult.Failure -> ""
            }
        _state.update {
            it.copy(
                active = true,
                displayName = displayName,
                step = if (displayName.isBlank()) OnboardingStep.Name else OnboardingStep.Photo,
                errorMessage = null,
            )
        }
    }

    /**
     * Post-auth session entry point: re-enter an interrupted stepper, then decide
     * the add-a-photo nudge for this session. The nudge is deliberately skipped in
     * the same session the stepper runs — the user just saw (or will see) the photo
     * step, so the first nudge lands a session later (spec: bounded, non-naggy).
     */
    suspend fun onSessionReady() {
        resumeIfNeeded()
        evaluatePhotoNudge()
    }

    /**
     * Eligibility is decided once per session from persisted counters: never after a
     * dismiss, and at most [MAX_PHOTO_NUDGE_SESSIONS] impression sessions total. The
     * banner additionally requires a photoless profile at render time — that part is
     * live media state, not settings.
     */
    suspend fun evaluatePhotoNudge() {
        val progress = runCatching { progressStore.load() }.getOrNull() ?: return
        val eligible =
            progress.completed &&
                !progress.photoNudgeDismissed &&
                progress.photoNudgeSessionsSeen < MAX_PHOTO_NUDGE_SESSIONS
        _state.update { it.copy(photoNudgeEligible = eligible) }
    }

    /** Counts an impression session the first time the banner actually renders. */
    suspend fun recordPhotoNudgeShown() {
        if (photoNudgeCountedThisSession) return
        photoNudgeCountedThisSession = true
        runCatching {
            val progress = progressStore.load()
            progressStore.save(progress.copy(photoNudgeSessionsSeen = progress.photoNudgeSessionsSeen + 1))
        }
    }

    suspend fun dismissPhotoNudge() {
        _state.update { it.copy(photoNudgeEligible = false) }
        runCatching { progressStore.save(progressStore.load().copy(photoNudgeDismissed = true)) }
    }

    private suspend fun markOnboardingInProgress(userId: String?) {
        runCatching {
            progressStore.save(
                progressStore.load().copy(completed = false, userId = userId),
            )
        }
    }

    private suspend fun markOnboardingCompleted() {
        runCatching {
            progressStore.save(
                progressStore.load().copy(completed = true, userId = null),
            )
        }
    }

    companion object {
        const val MINIMUM_AGE_YEARS = 18
        const val MIN_PASSWORD_LENGTH = 12
        const val MAX_PHOTO_NUDGE_SESSIONS = 3

        /** Wheel starting position: a plausible adult age, not the 18 boundary. */
        fun defaultDob(today: LocalDate): LocalDate = LocalDate(today.year - 25, today.month.number, 1)
    }
}

@OptIn(ExperimentalTime::class)
private fun systemToday(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

/** Calendar-correct age gate: 18 exactly on the 18th birthday, not the day before. */
internal fun LocalDate.isAtLeastYearsOld(
    years: Int,
    today: LocalDate,
): Boolean {
    val birthdayThisYear = year + years
    return when {
        today.year > birthdayThisYear -> true
        today.year < birthdayThisYear -> false
        today.month.number > month.number -> true
        today.month.number < month.number -> false
        else -> today.day >= day
    }
}

enum class OnboardingStep {
    Dob,
    UnderageStop,
    Account,
    Location,
    Name,
    Photo,
}

@Immutable
data class OnboardingUiState(
    val active: Boolean = false,
    val step: OnboardingStep = OnboardingStep.Dob,
    val dob: LocalDate,
    val email: String = "",
    val password: String = "",
    val acceptedTerms: Boolean = false,
    val acceptedPrivacy: Boolean = false,
    val displayName: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val photoNudgeEligible: Boolean = false,
)

interface OnboardingProfileRepository {
    suspend fun updateDisplayName(displayName: String): AppResult<MeDto>

    suspend fun getMe(): AppResult<MeDto>
}

class ApiOnboardingProfileRepository(
    private val apiClient: PynapliApiClient,
) : OnboardingProfileRepository {
    override suspend fun updateDisplayName(displayName: String): AppResult<MeDto> = apiClient.updateProfile(UpdateProfileRequestDto(displayName = displayName))

    override suspend fun getMe(): AppResult<MeDto> = apiClient.getMe()
}
