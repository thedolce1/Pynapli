package com.pynapli.server.contactblocks

import com.pynapli.server.grid.insertActiveProfileLink
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.ActivitySummaryDto
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Group conversations and the "Bring your partner" note, with an ACTIVE X: X is invited into a
 * conversation A started with C (C's partner), signs in while A's row is still pending, and writes
 * there. Two worlds (X's address is the one A added, or not): everything A sees is identical; in the
 * member world X sees the group with A stripped, never A's messages, and A's messages never move the
 * thread or change its preview or unread count. There is no quoting or reply-to feature, so nothing
 * of A's can surface inside someone else's message.
 */
class ContactBlockConversationsIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest { PynapliTestDatabase.reset() }

        "an active X in a group with A: A sees the same in both worlds; X never sees A, A's messages, or A's bumps" {
            runBlocking<Unit> {
                val member = groupWorld(dataSource, xEmail = BLOCKED_ADDRESS)
                PynapliTestDatabase.reset()
                val nobody = groupWorld(dataSource, xEmail = "riley.elsewhere@example.com")

                assertEquals(nobody.blocker.keys, member.blocker.keys)
                member.blocker.forEach { (step, seen) -> assertEquals(nobody.blocker.getValue(step), seen, "A's '$step' differs between the worlds") }

                with(member) {
                    assertEquals(setOf(A), hiddenFromX, "bound at X's sign-in")
                    val listed = xInbox.items.single()
                    assertTrue(listed.participants.none { it.userId == A.toString() }, "A stripped from the group")
                    assertTrue(listed.partnerInvites.none { it.inviterUserId == A.toString() || it.invitedPartnerUserId == A.toString() })
                    assertEquals("Hello all", listed.lastMessage?.body, "A's later message is not X's preview")
                    assertEquals(0, listed.unreadCount)
                    assertEquals(xSortBeforeAsMessage, listed.updatedAt, "A's message didn't move the thread for X")
                    assertTrue(xThread.messages.none { it.senderUserId == A.toString() })
                }
                with(nobody) {
                    assertTrue(xThread.messages.any { it.senderUserId == A.toString() }, "without a bound row X sees A's messages")
                }
            }
        }

        "the bring-your-partner note never names someone hiding from the viewer" {
            runBlocking<Unit> {
                val graph = ContactBlockTestGraph(dataSource)
                dataSource.insertDiscoverableUser(A, "Alex", age = 34, longitude = -122.0800, latitude = 37.4200)
                dataSource.insertDiscoverableUser(P, "Jo", age = 36, longitude = -122.0801, latitude = 37.4201)
                dataSource.insertDiscoverableUser(X, "Sam", age = 41, longitude = -122.0810, latitude = 37.4210)
                dataSource.insertActiveProfileLink(userId = P, linkedUserId = A)
                graph.execute(
                    "INSERT INTO matches (id, user_a, user_b) VALUES (gen_random_uuid(), LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid))",
                    P,
                    X,
                    P,
                    X,
                )
                graph.messaging.matchConversationOpener.openForMatch(P, X)
                val conversationId = graph.messaging.listConversations(X, null).items.single().conversationId
                val before = graph.messaging.markConversationRead(X, conversationId).partnerOffers
                assertTrue(before?.participantPartners.orEmpty().isNotEmpty(), "unbound, X is told P is linked with A")

                graph.verifyAddress(X, X_ADDRESS)
                graph.contactBlocks.add(A, X_ADDRESS)
                graph.resolver.resolveRecent()

                val after = graph.messaging.markConversationRead(X, conversationId).partnerOffers
                assertTrue(after?.participantPartners.orEmpty().isEmpty(), "bound, the note never names A")
            }
        }
    })

private class GroupWorld(
    val blocker: Map<String, String>,
    val hiddenFromX: Set<UUID>,
    val xInbox: ConversationListResponseDto,
    val xThread: ConversationDetailDto,
    val xSortBeforeAsMessage: String,
)

private const val BLOCKED_ADDRESS = "riley.partner@example.com"
private const val X_ADDRESS = "sam.offer@example.com"
private val A: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c5a1")
private val C: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c5a2")
private val X: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c5a3")
private val P: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c5a4")

private val json = Json { encodeDefaults = true }
private val MASKED = setOf("conversationId", "messageId", "inviteId", "createdAt", "updatedAt", "nextCursor", "latestBadgeOccurredAt")

private fun masked(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) -> if (key in MASKED) JsonPrimitive("<masked>") else masked(value) })
        is JsonArray -> JsonArray(element.map(::masked))
        else -> element
    }

private fun <T> snap(
    serializer: KSerializer<T>,
    value: T,
): String = masked(json.encodeToJsonElement(serializer, value)).toString()

private fun groupWorld(
    dataSource: javax.sql.DataSource,
    xEmail: String,
): GroupWorld =
    runBlocking {
        val graph = ContactBlockTestGraph(dataSource)
        dataSource.insertDiscoverableUser(A, "Alex", age = 34, longitude = -122.0800, latitude = 37.4200)
        dataSource.insertDiscoverableUser(C, "Casey", age = 37, longitude = -122.0802, latitude = 37.4202)
        dataSource.insertDiscoverableUser(X, "Riley", age = 39, longitude = -122.0810, latitude = 37.4210)
        dataSource.insertActiveProfileLink(userId = C, linkedUserId = X)
        graph.execute("UPDATE users SET email_verified_at = now() WHERE id = ?", X)
        graph.contactBlocks.add(A, BLOCKED_ADDRESS)
        // X signs in while A's row is pending: settled before the session (member world binds).
        ContactBlockSettling(graph.resolver, worker = null).atSignIn(X, xEmail)

        val seen = linkedMapOf<String, String>()
        val opened = graph.messaging.createDirectConversation(A, CreateDirectConversationRequestDto(targetUserId = C.toString()))
        val conversationId = opened.conversation.conversationId
        graph.messaging.sendMessage(A, conversationId, SendMessageRequestDto(body = "Hi Casey"))
        graph.messaging.sendMessage(C, conversationId, SendMessageRequestDto(body = "Hi Alex"))
        graph.messaging.inviteLinkedPartnerToConversation(C, conversationId)
        seen["approve"] = snap(ConversationDetailDto.serializer(), graph.messaging.acceptPartnerInvite(A, conversationId))
        graph.messaging.acceptPartnerInvite(X, conversationId)
        graph.messaging.sendMessage(X, conversationId, SendMessageRequestDto(body = "Hello all"))
        val xSortBefore = graph.messaging.listConversations(X, null).items.single().updatedAt
        graph.messaging.sendMessage(A, conversationId, SendMessageRequestDto(body = "Welcome Riley"))

        seen["inbox"] = snap(ConversationListResponseDto.serializer(), graph.messaging.listConversations(A, null))
        seen["thread"] = snap(ConversationDetailDto.serializer(), graph.messaging.getConversationDetail(A, conversationId))
        seen["activity"] = snap(ActivitySummaryDto.serializer(), graph.activity.getActivitySummary(A))
        seen["count"] = graph.contactBlocks.summary(A).count.toString()

        GroupWorld(
            blocker = seen,
            hiddenFromX = graph.visibility.hiddenFrom(X),
            xInbox = graph.messaging.listConversations(X, null),
            xThread = graph.messaging.getConversationDetail(X, conversationId),
            xSortBeforeAsMessage = xSortBefore,
        )
    }
