package com.pynapli.app.swipe

import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeRewindResponseDto
import kotlin.random.Random

internal const val MAX_PENDING_ACTIONS = 10
internal const val PINEAPPLE_NOTE_NOT_ACCEPTED = "PINEAPPLE_LIKE_NOTE_NOT_ACCEPTED"
internal const val PINEAPPLE_NOTE_TOO_LONG_MESSAGE = "Notes with a Pineapple Like are 140 characters or less."

// Prefetch the next 30-card page once this many cards remain. Tuned for "never wait for a card"
// (SWIPE_IMPLEMENTATION_SPEC perf goal): a 10-card runway is several seconds even for very fast
// swipers, so the next page lands well before the deck empties on normal networks.
internal const val LOAD_MORE_THRESHOLD = 10

internal fun QueuedSwipeAction.paywallMessageFor(errorCode: String): String? =
    when (errorCode) {
        "ENTITLEMENT_REQUIRED" -> "Social or Select is required."
        "PINEAPPLE_LIKE_INVENTORY_REQUIRED" ->
            if (action == SwipeAction.PINEAPPLE_LIKE && noteText != null) {
                "You're out of Pineapple Likes. Get more to send your note."
            } else {
                "You're out of Pineapple Likes. Get more to stand out."
            }
        else -> null
    }

internal fun SwipeRewindResponseDto.toUi(): SwipeRewindUi? {
    val restoredCard = card ?: return null
    if (!rewound) return null
    return SwipeRewindUi(deckSessionId = deckSessionId, card = restoredCard)
}

internal fun List<SwipeCardDto>.withRestoredTopCard(card: SwipeCardDto): List<SwipeCardDto> =
    if (any { it.userId == card.userId }) {
        this
    } else {
        listOf(card) + this
    }

internal fun newSwipeActionId(random: Random = Random.Default): String {
    val bytes = random.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex =
        bytes.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    return buildString(36) {
        append(hex.substring(0, 8))
        append('-')
        append(hex.substring(8, 12))
        append('-')
        append(hex.substring(12, 16))
        append('-')
        append(hex.substring(16, 20))
        append('-')
        append(hex.substring(20, 32))
    }
}
