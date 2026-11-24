package com.pynapli.app.contactblocks

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ContactBlockSummaryDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactBlocksStateHolderTest {
    /** Stands in for the server: it keeps a count, nothing about who an address belongs to. */
    private class FakeRepository : ContactBlocksRepository {
        val added = mutableSetOf<String>()
        val calls = mutableListOf<String>()
        var failNext: AppError? = null

        override suspend fun loadSummary(): AppResult<ContactBlockSummaryDto> = AppResult.Success(ContactBlockSummaryDto(count = added.size, maxAddresses = 1_000))

        override suspend fun add(email: String): AppResult<Unit> {
            calls += "add:$email"
            failNext?.let {
                failNext = null
                return AppResult.Failure(it)
            }
            added += email.lowercase()
            return AppResult.Success(Unit)
        }

        override suspend fun remove(email: String): AppResult<Unit> {
            calls += "remove:$email"
            added -= email.lowercase()
            return AppResult.Success(Unit)
        }

        override suspend fun removeAll(): AppResult<Unit> {
            calls += "removeAll"
            added.clear()
            return AppResult.Success(Unit)
        }
    }

    @Test
    fun loadShowsTheCount() =
        runTest {
            val repository = FakeRepository().apply { added += "a@example.com" }
            val holder = ContactBlocksStateHolder(repository)

            holder.load()

            assertEquals(1, holder.state.value.count)
            assertEquals("1 address added", holder.state.value.countLabel)
        }

    @Test
    fun addSendsTheTrimmedAddressAndRefreshesTheCount() =
        runTest {
            val repository = FakeRepository()
            val holder = ContactBlocksStateHolder(repository)

            assertTrue(holder.add("  boss@example.com "))
            assertTrue(holder.add("other@example.com"))

            assertEquals(listOf("add:boss@example.com", "add:other@example.com"), repository.calls)
            assertEquals(2, holder.state.value.count)
            assertEquals("2 addresses added", holder.state.value.countLabel)
            assertEquals("Added.", holder.state.value.confirmation)
            assertFalse(holder.state.value.isSaving)
        }

    @Test
    fun removeByTypingItAgainAndRemoveAll() =
        runTest {
            val repository = FakeRepository()
            val holder = ContactBlocksStateHolder(repository)
            holder.add("boss@example.com")
            holder.add("other@example.com")

            assertTrue(holder.remove("boss@example.com"))
            assertEquals(1, holder.state.value.count)
            assertEquals("If that address was on your list, it's been removed.", holder.state.value.confirmation)

            assertTrue(holder.removeAll())
            assertEquals(0, holder.state.value.count)
            assertEquals("0 addresses added", holder.state.value.countLabel)
            assertEquals("All addresses removed.", holder.state.value.confirmation)
        }

    @Test
    fun aRefusedAddShowsTheServerMessageAndKeepsTheCount() =
        runTest {
            val repository = FakeRepository()
            val holder = ContactBlocksStateHolder(repository)
            holder.add("boss@example.com")
            repository.failNext = AppError(code = "INVALID_EMAIL", message = "Enter a valid email address.")

            assertFalse(holder.add("not an address"))

            assertEquals("Enter a valid email address.", holder.state.value.errorMessage)
            assertNull(holder.state.value.confirmation)
            assertEquals(1, holder.state.value.count)
            holder.clearMessages()
            assertNull(holder.state.value.errorMessage)
        }
}
