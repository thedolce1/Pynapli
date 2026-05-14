package com.pynapli.app.swipe

import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.storage.PynapliDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface SwipeActionQueueStore {
    suspend fun load(ownerUserId: String?): List<QueuedSwipeAction>

    suspend fun save(ownerUserId: String?, actions: List<QueuedSwipeAction>)

    suspend fun clear(ownerUserId: String?)
}

object NoOpSwipeActionQueueStore : SwipeActionQueueStore {
    override suspend fun load(ownerUserId: String?): List<QueuedSwipeAction> = emptyList()

    override suspend fun save(ownerUserId: String?, actions: List<QueuedSwipeAction>) = Unit

    override suspend fun clear(ownerUserId: String?) = Unit
}

class SqlDelightSwipeActionQueueStore(
    private val database: PynapliDatabase,
    private val json: Json = PynapliJson
) : SwipeActionQueueStore {
    override suspend fun load(ownerUserId: String?): List<QueuedSwipeAction> {
        val value = database.appMetaQueries.selectByKey(ownerUserId.queueKey()).executeAsOneOrNull()?.value_
            ?: return emptyList()
        return runCatching {
            json.decodeFromString(SwipeActionQueuePayload.serializer(), value).actions.map { it.toQueuedAction() }
        }.getOrElse { emptyList() }
    }

    override suspend fun save(ownerUserId: String?, actions: List<QueuedSwipeAction>) {
        if (actions.isEmpty()) {
            clear(ownerUserId)
            return
        }
        val payload = SwipeActionQueuePayload(
            actions = actions.map { it.toPersistedAction() }
        )
        database.appMetaQueries.upsert(
            key = ownerUserId.queueKey(),
            value_ = json.encodeToString(SwipeActionQueuePayload.serializer(), payload),
            updated_at_epoch_ms = currentTimeMillis()
        )
    }

    override suspend fun clear(ownerUserId: String?) {
        database.appMetaQueries.deleteByKey(ownerUserId.queueKey())
    }

    private fun QueuedSwipeAction.toPersistedAction(): PersistedQueuedSwipeAction =
        PersistedQueuedSwipeAction(
            actionId = actionId,
            deckSessionId = deckSessionId,
            card = PersistedSwipeCard(
                userId = card.userId,
                displayName = card.displayName,
                displayAge = card.displayAge,
                distanceLabel = card.distanceLabel,
                distanceMeters = card.distanceMeters,
                photoUrl = card.photoUrl,
                linkedPartner = card.linkedPartner,
                rankToken = card.rankToken
            ),
            action = action,
            noteText = noteText
        )

    private fun PersistedQueuedSwipeAction.toQueuedAction(): QueuedSwipeAction =
        QueuedSwipeAction(
            actionId = actionId,
            deckSessionId = deckSessionId,
            card = SwipeCardDto(
                userId = card.userId,
                displayName = card.displayName,
                displayAge = card.displayAge,
                distanceLabel = card.distanceLabel,
                distanceMeters = card.distanceMeters,
                photoUrl = card.photoUrl,
                linkedPartner = card.linkedPartner,
                rankToken = card.rankToken
            ),
            action = action,
            noteText = noteText
        )

    companion object {
        private const val QUEUE_KEY_PREFIX = "swipe.pending.actions.v1"

        private fun String?.queueKey(): String {
            val owner = this?.trim()?.takeIf { it.isNotEmpty() } ?: "anonymous"
            return "$QUEUE_KEY_PREFIX:$owner"
        }

        @OptIn(ExperimentalTime::class)
        private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
    }
}

@Serializable
private data class SwipeActionQueuePayload(
    val version: Int = 1,
    val actions: List<PersistedQueuedSwipeAction>
)

@Serializable
private data class PersistedQueuedSwipeAction(
    val actionId: String,
    val deckSessionId: String?,
    val card: PersistedSwipeCard,
    val action: String,
    val noteText: String?
)

@Serializable
private data class PersistedSwipeCard(
    val userId: String,
    val displayName: String,
    val displayAge: Int?,
    val distanceLabel: String?,
    val distanceMeters: Int?,
    val photoUrl: String?,
    val linkedPartner: LinkedPartnerMiniDto?,
    val rankToken: String
)
