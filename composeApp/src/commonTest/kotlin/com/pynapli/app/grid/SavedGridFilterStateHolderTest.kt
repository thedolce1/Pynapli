package com.pynapli.app.grid

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CreateSavedGridFilterRequestDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.SavedGridFilterDto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedGridFilterStateHolderTest {

    @Test
    fun loadHydratesSavedFilters() = runBlocking {
        val item = sampleDto(id = "abc", name = "Verified only")
        val repository = FakeRepository(listResult = AppResult.Success(listOf(item)))
        val stateHolder = SavedGridFilterStateHolder(repository)

        stateHolder.load()

        val state = stateHolder.state.value
        assertEquals(listOf("abc"), state.items.map { it.id })
        assertEquals(false, state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun loadEntitlementRequiredClearsItemsAndDoesNotSurfaceError() = runBlocking {
        val repository = FakeRepository(
            listResult = AppResult.Failure(AppError("ENTITLEMENT_REQUIRED", "Upgrade required"))
        )
        val stateHolder = SavedGridFilterStateHolder(repository)

        stateHolder.load()

        val state = stateHolder.state.value
        assertTrue(state.items.isEmpty())
        assertTrue(state.entitlementRequired)
        assertNull(state.errorMessage)
    }

    @Test
    fun saveAddsItemToTopOfList() = runBlocking {
        val existing = sampleDto(id = "old", name = "Old", createdAt = "2026-05-01T00:00:00Z")
        val created = sampleDto(id = "new", name = "Latest", createdAt = "2026-05-09T00:00:00Z")
        val repository = FakeRepository(
            listResult = AppResult.Success(listOf(existing)),
            createResult = AppResult.Success(created)
        )
        val stateHolder = SavedGridFilterStateHolder(repository)

        stateHolder.load()
        stateHolder.save("Latest", GridFilters(verified = true))

        assertEquals(listOf("new", "old"), stateHolder.state.value.items.map { it.id })
        assertNull(stateHolder.state.value.errorMessage)
    }

    @Test
    fun saveBlankNameSurfacesValidationError() = runBlocking {
        val repository = FakeRepository(listResult = AppResult.Success(emptyList()))
        val stateHolder = SavedGridFilterStateHolder(repository)
        stateHolder.load()

        stateHolder.save("   ", GridFilters.defaults)

        assertEquals("INVALID_SAVED_FILTER_NAME", stateHolder.state.value.errorCode)
        assertEquals(0, repository.createInvocations)
    }

    @Test
    fun deleteRemovesItem() = runBlocking {
        val a = sampleDto(id = "a", name = "A")
        val b = sampleDto(id = "b", name = "B")
        val repository = FakeRepository(
            listResult = AppResult.Success(listOf(a, b)),
            deleteResult = AppResult.Success(Unit)
        )
        val stateHolder = SavedGridFilterStateHolder(repository)
        stateHolder.load()

        stateHolder.delete("a")

        assertEquals(listOf("b"), stateHolder.state.value.items.map { it.id })
    }

    private fun sampleDto(
        id: String,
        name: String,
        createdAt: String = "2026-05-09T00:00:00Z"
    ): SavedGridFilterDto = SavedGridFilterDto(
        id = id,
        name = name,
        payload = GridQueryRequestDto(),
        createdAt = createdAt,
        updatedAt = createdAt
    )
}

private class FakeRepository(
    private val listResult: AppResult<List<SavedGridFilterDto>> = AppResult.Success(emptyList()),
    private val createResult: AppResult<SavedGridFilterDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "no")),
    private val deleteResult: AppResult<Unit> = AppResult.Success(Unit)
) : SavedGridFilterRepository {
    var createInvocations: Int = 0
        private set

    override suspend fun list(): AppResult<List<SavedGridFilterDto>> = listResult
    override suspend fun create(request: CreateSavedGridFilterRequestDto): AppResult<SavedGridFilterDto> {
        createInvocations += 1
        return createResult
    }
    override suspend fun delete(id: String): AppResult<Unit> = deleteResult
}
