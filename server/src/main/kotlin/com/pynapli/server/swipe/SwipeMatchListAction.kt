package com.pynapli.server.swipe

import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.shared.network.dto.MatchesListResponseDto
import java.util.UUID

internal class SwipeMatchListAction(
    private val repository: SwipeRepository,
    private val dtoMapper: SwipeDtoMapper,
    private val blockRepository: BlockRepository?,
    private val riskPolicyService: RiskPolicyService?,
) {
    /** One page of matches, newest first; `nextCursor` continues it and is null on the last page. */
    suspend fun listMatches(
        viewerUserId: UUID,
        cursorText: String? = null,
    ): MatchesListResponseDto {
        val after = cursorText?.takeIf { it.isNotBlank() }?.let(::decodeMatchCursor)
        val fetched = repository.listMatches(viewerUserId, SWIPE_MATCHES_LIMIT + 1, after)
        val rows = fetched.take(SWIPE_MATCHES_LIMIT)
        val nextCursor = if (fetched.size > SWIPE_MATCHES_LIMIT) rows.last().let { encodeMatchCursor(MatchPageCursor(it.createdAt, it.matchId)) } else null
        val blockedRelatedIds = blockRepository?.listBlockedRelatedUserIds(viewerUserId).orEmpty()
        val blockVisible =
            if (blockedRelatedIds.isEmpty()) {
                rows
            } else {
                rows.filterNot { it.matchedUserId in blockedRelatedIds }
            }
        val visible =
            if (riskPolicyService == null) {
                blockVisible
            } else {
                buildList {
                    for (record in blockVisible) {
                        if (!riskPolicyService.shouldHideFromDiscovery(record.matchedUserId)) {
                            add(record)
                        }
                    }
                }
            }
        return MatchesListResponseDto(
            matches = visible.map { record -> dtoMapper.matchSummaryToDto(record) },
            nextCursor = nextCursor,
        )
    }
}

private fun encodeMatchCursor(cursor: MatchPageCursor): String =
    java.util.Base64
        .getUrlEncoder()
        .withoutPadding()
        .encodeToString("${cursor.createdAt}|${cursor.matchId}".toByteArray(Charsets.UTF_8))

private fun decodeMatchCursor(text: String): MatchPageCursor =
    runCatching {
        val decoded =
            String(
                java.util.Base64
                    .getUrlDecoder()
                    .decode(text),
                Charsets.UTF_8,
            )
        val separator = decoded.indexOf('|')
        require(separator > 0 && separator < decoded.lastIndex)
        MatchPageCursor(java.time.Instant.parse(decoded.substring(0, separator)), UUID.fromString(decoded.substring(separator + 1)))
    }.getOrElse { throw SwipeException("INVALID_MATCH_CURSOR") }
