package com.pynapli.app.grid

import com.pynapli.shared.network.dto.GridCardDto
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** G2-2 ticket 2: the server's stable image keys reach the tile model; a locked tile never keeps one. */
class GridCardCacheKeyMappingTest {
    @Test
    fun theTileAndThePartnerMiniKeepTheServersKeys() {
        val ui =
            GridCardDto(
                userId = "u1",
                displayName = "Maya",
                thumbnailUrl = "https://cdn/a?sig=1",
                thumbnailCacheKey = "asset-a:v1-thumb-480",
                online = true,
                rankToken = "grid:v1:u1",
                linkedPartner = LinkedPartnerMiniDto(userId = "p1", thumbnailUrl = "https://cdn/p?sig=1", thumbnailCacheKey = "asset-p:orig"),
            ).toGridProfileCardUi(0)

        assertEquals("asset-a:v1-thumb-480", ui.thumbnailCacheKey)
        assertEquals("asset-p:orig", ui.linkedPartner?.thumbnailCacheKey)
    }

    @Test
    fun aLockedTileIsCachedByUrlOnly() {
        val ui =
            GridCardDto(
                userId = "locked_abc",
                displayName = "",
                thumbnailUrl = "https://cdn/a?sig=1",
                thumbnailCacheKey = "asset-a:v1-thumb-480",
                online = false,
                rankToken = "locked_abc",
                locked = true,
            ).toGridProfileCardUi(0)

        assertEquals("https://cdn/a?sig=1", ui.thumbnailUrl)
        assertNull(ui.thumbnailCacheKey)
    }
}
