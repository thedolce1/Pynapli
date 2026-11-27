package com.pynapli.app

import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.shared.network.dto.ConversationParticipantDto

internal fun ConversationParticipantDto.toConversationProfileCardUi(): GridProfileCardUi =
    GridProfileCardUi(
        id = userId,
        displayName = displayName,
        online = online,
        distanceLabel = null,
    )
