package com.pynapli.app.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.conversation.titleFor
import com.pynapli.app.design.PynapliColors
import com.pynapli.shared.network.dto.ConversationDto

internal object NewMatchesRailTags {
    const val RAIL = "messages-new-matches-rail"

    fun avatar(conversationId: String): String = "messages-new-match-$conversationId"
}

/**
 * A swipe match creates a conversation immediately, so "new matches" are simply swipe-origin
 * conversations with no messages yet. Surfacing them as a Tinder-style horizontal rail at the top
 * of Messages (founder direction, 2026-06-07) replaces the old Activity Matches tab and nudges the
 * user to send the first message.
 */
internal fun ConversationDto.isNewMatch(): Boolean = origin == "swipe" && lastMessage == null

@Composable
internal fun MessagesNewMatchesRail(
    matches: List<ConversationDto>,
    viewerUserId: String?,
    onMatchSelected: (ConversationDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (matches.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth().testTag(NewMatchesRailTags.RAIL),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "New Matches",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 18.dp, top = 6.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(
                items = matches,
                key = { match -> match.conversationId },
            ) { match ->
                NewMatchAvatar(
                    name = match.titleFor(viewerUserId),
                    online = match.participants.firstOrNull { it.userId != viewerUserId }?.online == true,
                    seed = match.conversationId,
                    onClick = { onMatchSelected(match) },
                    modifier = Modifier.testTag(NewMatchesRailTags.avatar(match.conversationId)),
                )
            }
        }
    }
}

@Composable
private fun NewMatchAvatar(
    name: String,
    online: Boolean,
    seed: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(68.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(modifier = Modifier.size(64.dp)) {
            Box(
                modifier =
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(avatarBrush(seed))
                        .border(2.dp, PynapliColors.Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.initials(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (online) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(15.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF18D77A))
                            .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                )
            }
        }
        Text(
            text = name,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private fun String.initials(): String =
    trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "?" }

private fun avatarBrush(seed: String): Brush {
    val palettes =
        listOf(
            listOf(Color(0xFF2A2A2A), Color(0xFF7D5B3F)),
            listOf(Color(0xFF263238), Color(0xFF607D8B)),
            listOf(Color(0xFF1E1B1B), Color(0xFFB85C38)),
            listOf(Color(0xFF252525), Color(0xFF455A40)),
            listOf(Color(0xFF2B2622), Color(0xFF9A6B38)),
            listOf(Color(0xFF2E2524), Color(0xFF884C5E)),
        )
    val index = seed.fold(0) { total, char -> total + char.code }.mod(palettes.size)
    return Brush.linearGradient(palettes[index])
}
