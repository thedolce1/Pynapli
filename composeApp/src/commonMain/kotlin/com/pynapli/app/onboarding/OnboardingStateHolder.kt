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
 * Drives the DOB-first signup stepper (ONBOARDING_AUTH_SPEC), aligned to the
 * founder's Grindr reference capture (2026-06-10): the birthday is typed into
 * DD/MM/YYYY fields (DD/MM/YYYY ordering founder-confirmed for the future Europe
 * launch), policies are reviewed on a dedicated step with read links and a single
 * Accept, and photo + display name share one skippable "Your profile" step.
 *
 * Steps: Dob -> (UnderageStop) -> Policies -> Account -> Location -> Profile -> grid.
 * An underage visitor is stopped before any account data is collected. Everything
 * after Account is skippable — "do it all later" lands on the grid in seconds.
 * Sign-in mode bypasses the stepper entirely.
 *
 * Resume (spec "app closed mid-signup"): signup flips the device-local
 * [OnboardingProgressStore] `completed` flag false (pinned to the signing-up
 * account); [resumeIfNeeded] re-enters at the Profile step on next launch until
 * the user finishes, which flips the flag back. Plain sign-ins never see the
 * stepper: the flag defaults true and only signup clears it.
 */
class OnboardingStateHolder(
    private val authStateHolder: AuthStateHolder,
    private val profileRepository: OnboardingProfileRepository,
    private val progressStore: OnboardingProgressStore,
    private val todayProvider: () -> LocalDate = ::systemToday,
) {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    // Per-process guard so a nudge impression counts one settings session, not one
    // per recomposition of the banner.
    private var photoNudgeCountedThisSession = false

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

    fun updateDobDay(value: String) {
        _state.update { it.copy(dobDay = value.digits(2), errorMessage = null) }
    }

    fun updateDobMonth(value: String) {
        _state.update { it.copy(dobMonth = value.digits(2), errorMessage = null) }
    }

    fun updateDobYear(value: String) {
        _state.update { it.copy(dobYear = value.digits(4), errorMessage = null) }
    }

    /** Validates the typed date, then gates 18+ before any account data is collected. */
    fun confirmDob() {
        val current = _state.value
        val dob = parseDob(current.dobDay, current.dobMonth, current.dobYear, todayProvider())
        if (dob == null) {
            _state.update { it.copy(errorMessage = "Enter a valid date of birth.") }
            return
        }
        val nextStep =
            if (dob.isAtLeastYearsOld(MINIMUM_AGE_YEARS, todayProvider())) {
                OnboardingStep.Policies
            } else {
                OnboardingStep.UnderageStop
            }
        _state.update { it.copy(dob = dob, step = nextStep, errorMessage = null) }
    }

    fun backToDob() {
        _state.update { it.copy(step = OnboardingStep.Dob, errorMessage = null) }
    }

    fun backToPolicies() {
        _state.update { it.copy(step = OnboardingStep.Policies, errorMessage = null) }
    }

    /**
     * The Policies step presents both documents with read links; one Accept tap
     * records both acceptances (Grindr-reference pattern) and the server contract
     * still receives them as separate explicit fields.
     */
    fun acceptPolicies() {
        _state.update {
            it.copy(
                acceptedTerms = true,
                acceptedPrivacy = true,
                step = OnboardingStep.Account,
                errorMessage = null,
            )
        }
    }

    fun updateEmail(value: String) {
        _state.update { it.copy(email = value, errorMessage = null) }
    }

    fun updatePassword(value: String) {
        _state.update { it.copy(password = value, errorMessage = null) }
    }

    fun updateDisplayName(value: String) {
        _state.update { it.copy(displayName = value.take(DISPLAY_NAME_MAX_LENGTH), errorMessage = null) }
    }

    /** Creates the account; on success enters the skippable post-auth steps. */
    suspend fun submitAccount() {
        val current = _state.value
        if (current.isSubmitting) return
        val email = current.email.trim()
        val dob = current.dob
        if (dob == null) {
            _state.update { it.copy(step = OnboardingStep.Dob, errorMessage = "Enter your date of birth.") }
            return
        }
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
                birthdate = dob.toString(),
                // The DOB step already gated 18+; passing it explicitly keeps the
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
        _state.update { it.copy(step = OnboardingStep.Profile, errorMessage = null) }
    }

    /**
     * Finishes the consolidated "Your profile" step: saves the display name when
     * one was typed (photo upload already ran through the media flow), then
     * releases the user to the grid. A blank name just skips — the spec keeps
     * every post-auth step optional.
     */
    suspend fun finishProfile() {
        val current = _state.value
        if (current.isSubmitting) return
        val name = current.displayName.trim()
        if (name.isEmpty()) {
            finish()
            return
        }
        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        when (val result = profileRepository.updateDisplayName(name)) {
            is AppResult.Success -> {
                _state.update { it.copy(isSubmitting = false, displayName = name) }
                finish()
            }

            is AppResult.Failure ->
                _state.update {
                    it.copy(isSubmitting = false, errorMessage = result.error.message)
                }
        }
    }

    /** Releases the user to the grid and marks onboarding complete. */
    suspend fun finish() {
        markOnboardingCompleted()
        _state.update { it.copy(active = false, errorMessage = null) }
    }

    /**
     * Post-auth session entry point: re-enter an interrupted stepper, then decide
     * the add-a-photo nudge for this session. The nudge is deliberately skipped in
     * the same session the stepper runs — the user just saw (or will see) the
     * profile step, so the first nudge lands a session later (spec: bounded,
     * non-naggy).
     */
    suspend fun onSessionReady() {
        resumeIfNeeded()
        evaluatePhotoNudge()
    }

    /**
     * Re-enters the consolidated Profile step after a mid-signup app close. No-op
     * for completed onboarding (the default) or unauthenticated sessions.
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
                // Offline resume: an empty prefill is harmless — saving again later
                // is idempotent.
                is AppResult.Failure -> ""
            }
        _state.update {
            it.copy(
                active = true,
                displayName = displayName,
                step = OnboardingStep.Profile,
                errorMessage = null,
            )
        }
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

    private fun String.digits(maxLength: Int): String = filter { it.isDigit() }.take(maxLength)

    companion object {
        const val MINIMUM_AGE_YEARS = 18
        const val MIN_PASSWORD_LENGTH = 12
        const val MAX_PHOTO_NUDGE_SESSIONS = 3
        const val DISPLAY_NAME_MAX_LENGTH = 15

        /**
         * Strict calendar parse of the typed DD/MM/YYYY fields: real day for the
         * month (leap-aware), and a sane year window (no future dates, no >120y).
         */
        fun parseDob(
            day: String,
            month: String,
            year: String,
            today: LocalDate,
        ): LocalDate? {
            val dayValue = day.toIntOrNull() ?: return null
            val monthValue = month.toIntOrNull() ?: return null
            val yearValue = year.takeIf { it.length == 4 }?.toIntOrNull() ?: return null
            if (yearValue !in (today.year - MAX_AGE_YEARS)..today.year) return null
            if (monthValue !in 1..12) return null
            if (dayValue !in 1..daysInMonth(yearValue, monthValue)) return null
            val date = LocalDate(yearValue, monthValue, dayValue)
            if (date > today) return null
            return date
        }

        internal fun daysInMonth(
            year: Int,
            month: Int,
        ): Int =
            when (month) {
                1, 3, 5, 7, 8, 10, 12 -> 31
                4, 6, 9, 11 -> 30
                else -> if (isLeapYear(year)) 29 else 28
            }

        internal fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

        private const val MAX_AGE_YEARS = 120
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
    Policies,
    Account,
    Location,
    Profile,
}

@Immutable
data class OnboardingUiState(
    val active: Boolean = false,
    val step: OnboardingStep = OnboardingStep.Dob,
    val dobDay: String = "",
    val dobMonth: String = "",
    val dobYear: String = "",
    val dob: LocalDate? = null,
    val email: String = "",
    val password: String = "",
    val acceptedTerms: Boolean = false,
    val acceptedPrivacy: Boolean = false,
    val displayName: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val photoNudgeEligible: Boolean = false,
) {
    val dobComplete: Boolean
        get() = dobDay.isNotEmpty() && dobMonth.isNotEmpty() && dobYear.length == 4
}

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
