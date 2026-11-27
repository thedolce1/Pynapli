package com.pynapli.app.profile

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileComposerStateHolderTest {
    @Test
    fun theComposerIsDisabledWhileSendingAndClearedOnSuccess() =
        runTest {
            val gate = CompletableDeferred<AppResult<String>>()
            val sends = mutableListOf<Triple<String, String, String>>()
            val holder =
                ProfileComposerStateHolder(
                    send = { target, body, key ->
                        sends += Triple(target, body, key)
                        gate.await()
                    },
                    idempotencyKeyFactory = { "key-1" },
                )
            holder.bind("user-a")
            holder.updateDraft("  hey there  ")
            assertTrue(holder.state.value.canSend)

            val result = async { holder.sendDraft() }
            runCurrent()
            assertTrue(holder.state.value.isSending)
            assertFalse(holder.state.value.canSend, "no second send while one is in flight")
            assertNull(holder.sendDraft(), "a tap during the send does nothing")

            gate.complete(AppResult.Success("conversation-1"))
            assertEquals("conversation-1", result.await())
            assertEquals(listOf(Triple("user-a", "hey there", "key-1")), sends)
            assertEquals("", holder.state.value.draft)
            assertFalse(holder.state.value.isSending)
            assertNull(holder.state.value.errorMessage)
        }

    @Test
    fun aFailureKeepsTheDraftAndErrorAndARetryReusesTheKey() =
        runTest {
            val keys = mutableListOf<String>()
            var answer: AppResult<String> = AppResult.Failure(AppError(code = "NETWORK", message = "Couldn't send."))
            var issued = 0
            val holder =
                ProfileComposerStateHolder(
                    send = { _, _, key ->
                        keys += key
                        answer
                    },
                    idempotencyKeyFactory = { "key-${++issued}" },
                )
            holder.bind("user-a")
            holder.updateDraft("hello")

            assertNull(holder.sendDraft())
            assertEquals("hello", holder.state.value.draft)
            assertEquals("Couldn't send.", holder.state.value.errorMessage)
            assertFalse(holder.state.value.isSending)

            // The same words again: same key, so a send whose answer was lost can't land twice.
            answer = AppResult.Success("conversation-1")
            assertEquals("conversation-1", holder.sendDraft())
            assertEquals(listOf("key-1", "key-1"), keys)

            // A new message gets a new key.
            holder.updateDraft("second")
            holder.sendDraft()
            assertEquals("key-2", keys.last())
        }

    @Test
    fun movingToAnotherProfileClearsTheDraftAndALateSendOpensNothing() =
        runTest {
            val gate = CompletableDeferred<AppResult<String>>()
            val holder = ProfileComposerStateHolder(send = { _, _, _ -> gate.await() }, idempotencyKeyFactory = { "k" })
            holder.bind("user-a")
            holder.updateDraft("for a")

            val result = async { holder.sendDraft() }
            runCurrent()
            holder.bind("user-b")
            assertEquals("", holder.state.value.draft)
            holder.updateDraft("for b")

            gate.complete(AppResult.Success("conversation-a"))
            assertNull(result.await(), "the host must not open a's thread over b's profile")
            assertEquals("for b", holder.state.value.draft)
            assertFalse(holder.state.value.isSending)
        }

    @Test
    fun blankOrUnboundDraftsDoNotSend() =
        runTest {
            var calls = 0
            val holder =
                ProfileComposerStateHolder(send = { _, _, _ ->
                    calls += 1
                    AppResult.Success("c")
                })
            holder.updateDraft("hi")
            assertNull(holder.sendDraft(), "no profile bound")
            holder.bind("user-a")
            holder.updateDraft("   ")
            assertFalse(holder.state.value.canSend)
            assertNull(holder.sendDraft())
            assertEquals(0, calls)
        }
}
