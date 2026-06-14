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
    suspend fun listMatches(viewerUserId: UUID): MatchesListResponseDto {
        val rows = repository.listMatches(viewerUserId, SWIPE_MATCHES_LIMIT)
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
        )
    }
}
