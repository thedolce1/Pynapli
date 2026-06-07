package com.pynapli.app.location

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import com.pynapli.shared.network.dto.TravelLocationDto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TravelLocationStateHolderTest {
    @Test
    fun setActiveAgeVerificationRequiredFlagsPromptAndSuppressesRawError() =
        runBlocking {
            // TravelLocationService throws AGE_VERIFICATION_REQUIRED for restricted regions when the
            // viewer is unverified. The holder must flag the verify prompt without leaving an active
            // travel row or a raw error toast (the sheet's verify CTA drives the next step).
            val repository =
                FakeRepository(
                    setResult = AppResult.Failure(AppError("AGE_VERIFICATION_REQUIRED", "Verify age")),
                )
            val stateHolder = TravelLocationStateHolder(repository)

            stateHolder.setActive(TravelCityPresets.ALL.first())

            val state = stateHolder.state.value
            assertTrue(state.ageVerificationRequired)
            assertFalse(state.entitlementRequired)
            assertNull(state.current, "a gated set must not produce an active travel row")
            assertNull(state.errorMessage)
            assertFalse(state.isSaving)
        }

    @Test
    fun setActiveEntitlementRequiredStillFlagsPaywallNotVerification() =
        runBlocking {
            val repository =
                FakeRepository(
                    setResult = AppResult.Failure(AppError("ENTITLEMENT_REQUIRED", "Upgrade")),
                )
            val stateHolder = TravelLocationStateHolder(repository)

            stateHolder.setActive(TravelCityPresets.ALL.first())

            val state = stateHolder.state.value
            assertTrue(state.entitlementRequired)
            assertFalse(state.ageVerificationRequired)
        }
}

private class FakeRepository(
    private val currentResult: AppResult<TravelLocationDto?> = AppResult.Success(null),
    private val setResult: AppResult<TravelLocationDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "no")),
    private val clearResult: AppResult<Unit> = AppResult.Success(Unit),
) : TravelLocationRepository {
    override suspend fun getCurrent(): AppResult<TravelLocationDto?> = currentResult

    override suspend fun setActive(request: SetTravelLocationRequestDto): AppResult<TravelLocationDto> = setResult

    override suspend fun clear(): AppResult<Unit> = clearResult
}
