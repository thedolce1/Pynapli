package com.pynapli.app.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliTheme
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationParticipantDto
import com.pynapli.shared.network.dto.MessageDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationScreenInteractionTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun conversationScreenUsesPremiumFullScreenSurface() = runComposeUiTest {
        var backCount = 0
        var sendCount = 0
        val state = ConversationUiState(
            viewerUserId = "viewer",
            detail = ConversationDetailDto(
                conversation = ConversationDto(
                    conversationId = "conversation-1",
                    kind = "direct",
                    origin = "grid",
                    participants = listOf(
                        ConversationParticipantDto("viewer", "You", online = true),
                        ConversationParticipantDto("mira", "Mira", online = true)
                    ),
                    unreadCount = 0,
                    updatedAt = "2026-04-24T18:10:00Z"
                ),
                messages = listOf(
                    MessageDto(
                        messageId = "message-1",
                        conversationId = "conversation-1",
                        senderUserId = "mira",
                        body = "Saw your King West tile.",
                        createdAt = "2026-04-24T18:02:00Z",
                        status = "visible",
                        deliveryStatus = "read"
                    ),
                    MessageDto(
                        messageId = "message-2",
                        conversationId = "conversation-1",
                        senderUserId = "viewer",
                        body = "Returning the smile. Drinks this week?",
                        createdAt = "2026-04-24T18:08:00Z",
                        status = "visible",
                        deliveryStatus = "read"
                    )
                )
            ),
            draft = "Thursday works."
        )

        setContent {
            PynapliTheme(themeMode = "dark") {
                Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                    ConversationScreen(
                        state = state,
                        onBack = { backCount += 1 },
                        onDraftChanged = {},
                        onSend = { sendCount += 1 },
                        onLoadOlder = {},
                        onDeleteMessage = {}
                    )
                }
            }
        }

        onNodeWithTag(ConversationTestTags.fullScreen).assertExists()
        onNodeWithTag(ConversationTestTags.fullScreenHeader).assertExists()
        onNodeWithText("Mira").assertExists()
        onNodeWithText("Online").assertExists()
        onNodeWithText("FROM GRID").assertExists()
        onNodeWithText("APR 24").assertExists()
        onNodeWithText("Saw your King West tile.").assertExists()
        onNodeWithText("Returning the smile. Drinks this week?").assertExists()
        onNodeWithTag(ConversationTestTags.fullScreen)
            .captureToImage()
            .assertConversationVisualSignature()

        onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backCount)

        onNodeWithContentDescription("Send message").performClick()
        assertEquals(1, sendCount)
    }
}

private fun ImageBitmap.assertConversationVisualSignature() {
    val signature = conversationVisualSignature()

    assertTrue(signature.darkRatio > 0.48f, "Conversation should keep a dark premium base: $signature")
    assertTrue(signature.surfaceRatio > 0.03f, "Conversation should render subdued raised surfaces: $signature")
    assertTrue(signature.goldPixels > 35, "Conversation should keep the gold outgoing-message accent: $signature")
    assertTrue(signature.greenPixels > 2, "Conversation should render online presence: $signature")
    assertTrue(signature.brightRatio > 0.006f, "Conversation should contain readable text/icons: $signature")
}

private fun ImageBitmap.conversationVisualSignature(sampleStep: Int = 4): ConversationVisualSignature {
    val pixels = toPixelMap()
    var sampled = 0
    var dark = 0
    var surface = 0
    var gold = 0
    var green = 0
    var bright = 0

    var y = 0
    while (y < pixels.height) {
        var x = 0
        while (x < pixels.width) {
            val color = pixels[x, y]
            val luminance = color.luminanceApprox()

            sampled += 1
            if (luminance < 0.13f) dark += 1
            if (luminance in 0.09f..0.24f && color.channelSpread() < 0.10f) surface += 1
            if (color.red > 0.82f && color.green > 0.62f && color.blue < 0.24f) gold += 1
            if (color.green > 0.62f && color.red < 0.24f && color.blue in 0.22f..0.62f) green += 1
            if (luminance > 0.74f) bright += 1

            x += sampleStep
        }
        y += sampleStep
    }

    return ConversationVisualSignature(
        darkRatio = dark.toFloat() / sampled,
        surfaceRatio = surface.toFloat() / sampled,
        goldPixels = gold,
        greenPixels = green,
        brightRatio = bright.toFloat() / sampled
    )
}

private fun Color.luminanceApprox(): Float =
    (0.2126f * red) + (0.7152f * green) + (0.0722f * blue)

private fun Color.channelSpread(): Float =
    maxOf(red, green, blue) - minOf(red, green, blue)

private data class ConversationVisualSignature(
    val darkRatio: Float,
    val surfaceRatio: Float,
    val goldPixels: Int,
    val greenPixels: Int,
    val brightRatio: Float
)
