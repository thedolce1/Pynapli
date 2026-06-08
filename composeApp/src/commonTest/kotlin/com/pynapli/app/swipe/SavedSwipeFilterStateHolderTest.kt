package com.pynapli.app.swipe

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CreateSavedSwipeFilterRequestDto
import com.pynapli.shared.network.dto.SavedSwipeFilterDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedSwipeFilterStateHolderTest {
    @Test
    fun loadAgeVerificationRequiredFlagsPromptAndSuppressesRawError() =
        runBlocking {
            // SavedSwipeFilterService throws AGE_VERIFICATION_REQUIRED for unverified viewers with
            // lifestyle presets. The holder must collapse items, flag the prompt, and not surface a
            // raw error toast (the verify prompt drives the next step instead).
            val repository =
                FakeRepository(
                    listResult = AppResult.Failure(AppError("AGE_VERIFICATION_REQUIRED", "Verify age")),
                )
            val stateHolder = SavedSwipeFilterStateHolder(repository)

            stateHolder.load()

            val state = stateHolder.state.value
            assertTrue(state.items.isEmpty())
            assertTrue(state.ageVerificationRequired)
            assertFalse(state.entitlementRequired)
            assertNull(state.errorMessage)
        }

    @Test
    fun saveAgeVerificationRequiredFlagsPromptAndRevertsToNoNewPreset() =
        runBlocking {
            val existing = sampleDto(id = "old", name = "Old")
            val repository =
                FakeRepository(
                    listResult = AppResult.Success(listOf(existing)),
                    createResult = AppResult.Failure(AppError("AGE_VERIFICATION_REQUIRED", "Verify age")),
                )
            val stateHolder = SavedSwipeFilterStateHolder(repository)

            stateHolder.load()
            stateHolder.save("Lifestyle", SwipeFilters.defaults)

            val state = stateHolder.state.value
            // The gated save did not add a preset; the prompt is flagged without a raw error.
            assertEquals(listOf("old"), state.items.map { it.id })
            assertTrue(state.ageVerificationRequired)
            assertNull(state.errorMessage)
            assertFalse(state.isSaving)
        }

    private fun sampleDto(
        id: String,
        name: String,
        createdAt: String = "2026-05-09T00:00:00Z",
    ): SavedSwipeFilterDto =
        SavedSwipeFilterDto(
            id = id,
            name = name,
            payload = SwipeDeckRequestDto(),
            createdAt = createdAt,
            updatedAt = createdAt,
        )
}

private class FakeRepository(
    private val listResult: AppResult<List<SavedSwipeFilterDto>> = AppResult.Success(emptyList()),
    private val createResult: AppResult<SavedSwipeFilterDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "no")),
    private val deleteResult: AppResult<Unit> = AppResult.Success(Unit),
) : SavedSwipeFilterRepository {
    override suspend fun list(): AppResult<List<SavedSwipeFilterDto>> = listResult

    override suspend fun create(request: CreateSavedSwipeFilterRequestDto): AppResult<SavedSwipeFilterDto> = createResult

    override suspend fun delete(id: String): AppResult<Unit> = deleteResult
}
