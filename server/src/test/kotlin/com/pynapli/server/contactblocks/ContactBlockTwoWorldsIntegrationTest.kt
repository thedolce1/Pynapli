package com.pynapli.server.contactblocks

import com.pynapli.server.account.JdbcAccountDataExportArchiver
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.ActivityFeedResponseDto
import com.pynapli.shared.network.dto.ActivitySummaryDto
import com.pynapli.shared.network.dto.ContactBlockSummaryDto
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.CreateProfileLinkInvitationRequestDto
import com.pynapli.shared.network.dto.FavoriteStatusDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import com.pynapli.shared.network.dto.LikeBackRequestDto
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.ProfileLinksSummaryDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeActionResponseDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.pynapli.shared.network.dto.SwipeDeckResponseDto
import com.pynapli.shared.network.dto.TonightFeedResponseDto
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The acceptance test of "Hide from people you know" (TRUST_AND_SAFETY_SPEC, "Contact blocks"):
 * NOTHING the blocking member A observes may depend on whether the address belongs to a member.
 *
 * The same scenario runs in two worlds that differ in one fact only: X's verified address.
 *  - World "member": X's address is the address A adds, so the row binds to X.
 *  - World "nobody": X's address is a different one, so A's address belongs to nobody.
 * Everything else (every id, profile, location, push device, and every action A takes toward X) is
 * the same in both. This is stronger than comparing X with a substitute profile: the target A acts on
 * is literally the same account with the same state, so any difference in what A receives can only
 * come from the contact block. Every response A gets is compared field for field after masking the
 * values that are random or clock-derived in any run (ids minted per request, timestamps, tokens).
 *
 * Then, in the "member" world only, X must never come across A anywhere, and nothing A caused (Activity
 * rows, counts, realtime deltas, pushes, conversations, invites, export rows) may reach X; the "nobody"
 * world shows each of those paths does deliver when nothing is bound, so the checks aren't vacuous.
 */
class ContactBlockTwoWorldsIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest { PynapliTestDatabase.reset() }

        "everything A sees is identical whether or not the address belongs to a member, and X never sees A" {
            runBlocking<Unit> {
                val member = runWorld(dataSource, xEmail = BLOCKED_ADDRESS)
                PynapliTestDatabase.reset()
                val nobody = runWorld(dataSource, xEmail = "someone.else@example.com")

                assertEquals(nobody.blockerObservations.keys, member.blockerObservations.keys)
                member.blockerObservations.forEach { (step, seen) ->
                    assertEquals(nobody.blockerObservations.getValue(step), seen, "A's '$step' differs between the worlds")
                }
                // A's message stays "sent" in both, exactly like an unread message.
                assertTrue(member.blockerObservations.getValue("message").contains("\"deliveryStatus\":\"sent\""))

                // The member world: X never comes across A, nothing A did reaches X.
                with(member.target) {
                    assertEquals(setOf(A), hiddenFromTarget)
                    assertFalse(A in gridIds, "A on X's Grid")
                    assertFalse(A in deckIds, "A in X's Swipe deck")
                    assertEquals("error:PROFILE_NOT_FOUND", profileOfBlocker)
                    assertFalse(A in activityActorIds, "A in X's Activity")
                    assertEquals(0, summary.profileViewCount + summary.flirtCount + summary.likeCount + summary.badgeCount)
                    assertTrue(inboxConversationIds.isEmpty(), "A's conversation listed for X")
                    assertEquals("error:CONVERSATION_NOT_FOUND", detailOfBlockersConversation)
                    assertEquals("error:CONVERSATION_NOT_FOUND", replyInBlockersConversation)
                    assertEquals("error:CONVERSATION_NOT_FOUND", readMarkOfBlockersConversation)
                    assertEquals(0, incomingLinkInvites)
                    assertEquals(0, outboxFromBlocker, "a push caused by A reached X")
                    assertEquals(0, activityDeltasToTarget, "an Activity delta caused by A reached X")
                    assertEquals(0, messageDeltasToTarget, "a message delta caused by A reached X")
                    assertEquals("error:PROFILE_NOT_FOUND", favoriteBlocker)
                    assertEquals("error:LIKE_BACK_NOT_AVAILABLE", likeBackBlocker)
                    assertEquals("error:MESSAGING_NOT_ALLOWED", openConversationWithBlocker)
                    assertEquals(0, exportRowsFromBlocker, "X's export shows rows A caused")
                }
                // The nobody world: the same paths do reach X when nothing is bound.
                with(nobody.target) {
                    assertTrue(hiddenFromTarget.isEmpty())
                    assertTrue(A in gridIds)
                    assertTrue(A in activityActorIds)
                    assertTrue(inboxConversationIds.isNotEmpty())
                    assertEquals(1, incomingLinkInvites)
                    assertTrue(outboxFromBlocker > 0)
                    assertTrue(activityDeltasToTarget > 0)
                    assertTrue(messageDeltasToTarget > 0)
                    assertTrue(exportRowsFromBlocker > 0)
                }
            }
        }
    })

private class TargetView(
    val hiddenFromTarget: Set<UUID>,
    val gridIds: Set<UUID>,
    val deckIds: Set<UUID>,
    val profileOfBlocker: String,
    val activityActorIds: Set<UUID>,
    val summary: ActivitySummaryDto,
    val inboxConversationIds: List<String>,
    val detailOfBlockersConversation: String,
    val replyInBlockersConversation: String,
    val readMarkOfBlockersConversation: String,
    val incomingLinkInvites: Int,
    val outboxFromBlocker: Int,
    val activityDeltasToTarget: Int,
    val messageDeltasToTarget: Int,
    val favoriteBlocker: String,
    val likeBackBlocker: String,
    val openConversationWithBlocker: String,
    val exportRowsFromBlocker: Int,
)

private class World(
    val blockerObservations: Map<String, String>,
    val target: TargetView,
)

private const val BLOCKED_ADDRESS = "boss@example.com"
private val A: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c0a1")
private val X: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c0a2")
private val OTHER_1: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c0a3")
private val OTHER_2: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c0a4")

private val json = Json { encodeDefaults = true }

/** Minted per request or read from the clock: different in any two runs, so not comparable. */
private val MASKED_KEYS =
    setOf(
        "conversationId", "messageId", "eventId", "linkId", "inviteId", "requestId", "actionId",
        "createdAt", "updatedAt", "occurredAt", "generatedAt", "latestBadgeOccurredAt", "lastActive",
        "deckSessionId", "rankToken", "cardToken", "nextCursor",
    )

private fun masked(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) -> if (key in MASKED_KEYS) JsonPrimitive("<masked>") else masked(value) })
        is JsonArray -> JsonArray(element.map(::masked))
        else -> element
    }

private suspend fun <T> observe(
    serializer: KSerializer<T>,
    block: suspend () -> T,
): String =
    try {
        masked(json.encodeToJsonElement(serializer, block())).toString()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: RuntimeException) {
        "error:${error.message}"
    }

private fun runWorld(
    dataSource: javax.sql.DataSource,
    xEmail: String,
): World =
    runBlocking {
        val graph = ContactBlockTestGraph(dataSource)
        dataSource.insertDiscoverableUser(A, "Alex", age = 34, longitude = -122.0800, latitude = 37.4200)
        dataSource.insertDiscoverableUser(X, "Sam", age = 41, longitude = -122.0810, latitude = 37.4210)
        dataSource.insertDiscoverableUser(OTHER_1, "Riley", age = 29, longitude = -122.0820, latitude = 37.4190)
        dataSource.insertDiscoverableUser(OTHER_2, "Jo", age = 38, longitude = -122.0790, latitude = 37.4220)
        graph.verifyAddress(A, "alex@example.com")
        graph.verifyAddress(X, xEmail)
        graph.registerPushDevice(X)
        val recorders = graph.recordRealtime(this)

        val seen = linkedMapOf<String, String>()
        val unit = Unit.serializer()
        seen["add"] = observe(unit) { graph.contactBlocks.add(A, " Boss@Example.com ") }
        // Off the request path (the worker does this after a random delay).
        graph.resolver.resolveRecent()
        seen["count"] = observe(ContactBlockSummaryDto.serializer()) { graph.contactBlocks.summary(A) }
        seen["grid"] = observe(GridQueryResponseDto.serializer()) { graph.grid.queryGrid(A, GridQueryRequestDto(radiusMeters = 50_000)) }
        val deck = graph.swipe.queryDeck(A, SwipeDeckRequestDto(radiusMeters = 50_000))
        seen["swipeDeck"] = masked(json.encodeToJsonElement(SwipeDeckResponseDto.serializer(), deck)).toString()
        seen["profileOfX"] = observe(PublicProfileDto.serializer()) { graph.profiles.getPublicProfile(A, X) }
        val xCard = deck.items.single { it.userId == X.toString() }
        seen["like"] =
            observe(SwipeActionResponseDto.serializer()) {
                graph.swipe.submitAction(
                    A,
                    SwipeActionRequestDto(
                        actionId = UUID.randomUUID().toString(),
                        deckSessionId = deck.deckSessionId,
                        cardToken = xCard.rankToken,
                        targetUserId = X.toString(),
                        action = "like",
                    ),
                )
            }
        seen["flirt"] = observe(SendFlirtResponseDto.serializer()) { graph.activity.sendFlirt(A, X) }
        seen["secondFlirt"] = observe(SendFlirtResponseDto.serializer()) { graph.activity.sendFlirt(A, X) }
        val opened = graph.messaging.createDirectConversation(A, CreateDirectConversationRequestDto(targetUserId = X.toString()))
        seen["openConversation"] = masked(json.encodeToJsonElement(ConversationDetailDto.serializer(), opened)).toString()
        val conversationId = opened.conversation.conversationId
        seen["message"] =
            observe(MessageDto.serializer()) {
                graph.messaging.sendMessage(A, conversationId, SendMessageRequestDto(body = "Hello there, nice to meet you"))
            }
        seen["inbox"] = observe(ConversationListResponseDto.serializer()) { graph.messaging.listConversations(A, null) }
        seen["thread"] = observe(ConversationDetailDto.serializer()) { graph.messaging.getConversationDetail(A, conversationId) }
        seen["activity"] = observe(ActivityFeedResponseDto.serializer()) { graph.activity.listActivity(A) }
        seen["activitySummary"] = observe(ActivitySummaryDto.serializer()) { graph.activity.getActivitySummary(A) }
        seen["tonight"] = observe(TonightFeedResponseDto.serializer()) { graph.tonight.feed(A, 50_000, null, 50) }
        seen["partnerInvite"] =
            observe(ProfileLinksSummaryDto.serializer()) {
                graph.profileLinks.createInvitation(A, CreateProfileLinkInvitationRequestDto(targetUserId = X.toString()))
            }
        seen["favorite"] = observe(FavoriteStatusDto.serializer()) { graph.favorites.add(A, X.toString()) }
        seen["gridAfter"] = observe(GridQueryResponseDto.serializer()) { graph.grid.queryGrid(A, GridQueryRequestDto(radiusMeters = 50_000)) }
        yield()

        // X's side, before A changes anything about the block.
        val target =
            TargetView(
                hiddenFromTarget = graph.visibility.hiddenFrom(X),
                gridIds = graph.grid.queryGrid(X, GridQueryRequestDto(radiusMeters = 50_000)).items.map { UUID.fromString(it.userId) }.toSet(),
                deckIds = graph.swipe.queryDeck(X, SwipeDeckRequestDto(radiusMeters = 50_000)).items.map { UUID.fromString(it.userId) }.toSet(),
                profileOfBlocker = observe(PublicProfileDto.serializer()) { graph.profiles.getPublicProfile(X, A) },
                activityActorIds = graph.activity.listActivity(X).items.map { UUID.fromString(it.actorUserId) }.toSet(),
                summary = graph.activity.getActivitySummary(X),
                inboxConversationIds = graph.messaging.listConversations(X, null).items.map { it.conversationId },
                detailOfBlockersConversation = observe(ConversationDetailDto.serializer()) { graph.messaging.getConversationDetail(X, conversationId) },
                replyInBlockersConversation =
                    observe(MessageDto.serializer()) { graph.messaging.sendMessage(X, conversationId, SendMessageRequestDto(body = "hi")) },
                readMarkOfBlockersConversation =
                    observe(com.pynapli.shared.network.dto.ConversationDto.serializer()) { graph.messaging.markConversationRead(X, conversationId) },
                incomingLinkInvites = graph.profileLinks.getSummary(X).incomingInvites.size,
                outboxFromBlocker = graph.outboxCount(recipient = X, actor = A),
                activityDeltasToTarget = graph.activityDeltas.count { it.recipientUserId == X },
                messageDeltasToTarget = graph.messageDeltas.count { it.recipientUserId == X },
                favoriteBlocker = observe(FavoriteStatusDto.serializer()) { graph.favorites.add(X, A.toString()) },
                likeBackBlocker =
                    if (xEmail == BLOCKED_ADDRESS) {
                        observe(SwipeActionResponseDto.serializer()) { graph.swipe.likeBack(X, LikeBackRequestDto(targetUserId = A.toString())) }
                    } else {
                        "not run"
                    },
                openConversationWithBlocker =
                    if (xEmail == BLOCKED_ADDRESS) {
                        observe(ConversationDetailDto.serializer()) {
                            graph.messaging.createDirectConversation(X, CreateDirectConversationRequestDto(targetUserId = A.toString()))
                        }
                    } else {
                        "not run"
                    },
                exportRowsFromBlocker = exportActivityRowsFrom(dataSource, graph, exporter = X, actor = A),
            )

        // Removing and re-adding, always the same answers for A.
        seen["removeUnknown"] = observe(unit) { graph.contactBlocks.remove(A, "nobody.at.all@example.com") }
        seen["addDuplicate"] = observe(unit) { graph.contactBlocks.add(A, BLOCKED_ADDRESS) }
        seen["countAfterDuplicate"] = observe(ContactBlockSummaryDto.serializer()) { graph.contactBlocks.summary(A) }
        seen["invalid"] = observe(unit) { graph.contactBlocks.add(A, "not an address") }
        seen["removeAll"] = observe(unit) { graph.contactBlocks.removeAll(A) }
        seen["countAfterRemoveAll"] = observe(ContactBlockSummaryDto.serializer()) { graph.contactBlocks.summary(A) }

        recorders.forEach { it.cancel() }
        World(blockerObservations = seen, target = target)
    }

private suspend fun exportActivityRowsFrom(
    dataSource: javax.sql.DataSource,
    graph: ContactBlockTestGraph,
    exporter: UUID,
    actor: UUID,
): Int {
    val archive = JdbcAccountDataExportArchiver(dataSource, contactBlockTokens = graph.tokens).buildArchive(exporter)
    val document = Json.parseToJsonElement(archive.bytes.decodeToString()).jsonObject
    return (document["activity"] as JsonArray).count { row -> row.jsonObject["actor_user_id"]?.jsonPrimitive?.content == actor.toString() }
}
