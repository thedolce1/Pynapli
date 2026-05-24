package com.pynapli.app

import com.pynapli.app.grid.GridCardTone
import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.shared.network.dto.ConversationParticipantDto

internal fun ConversationParticipantDto.toConversationProfileCardUi(): GridProfileCardUi =
    GridProfileCardUi(
        id = userId,
        displayName = displayName,
        online = online,
        distanceLabel = null,
        tone = GridCardTone.entries[userId.stableIndex(GridCardTone.entries.size)]
    )

private fun String.stableIndex(size: Int): Int =
    fold(0) { total, char -> total + char.code }.mod(size)
