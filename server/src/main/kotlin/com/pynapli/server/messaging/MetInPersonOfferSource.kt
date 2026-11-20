package com.pynapli.server.messaging

import com.pynapli.shared.network.dto.MetInPersonOfferDto
import java.util.UUID

/**
 * The viewer's own "Met in person" actions for a conversation, attached to the read mark
 * (`ConversationDto.metInPersonOffers`). Implemented by `MetInPersonService`; kept as a seam so
 * messaging does not depend on that module.
 */
fun interface MetInPersonOfferSource {
    suspend fun offersFor(
        viewerUserId: UUID,
        conversationId: UUID,
    ): List<MetInPersonOfferDto>
}
