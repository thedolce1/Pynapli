package com.pynapli.server.swipe

import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.shared.network.dto.MarkMatchesCelebratedRequestDto
import com.pynapli.shared.network.dto.MarkMatchesCelebratedResponseDto
import com.pynapli.shared.network.dto.MatchCelebrationDto
import com.pynapli.shared.network.dto.MatchCelebrationsResponseDto
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * The once-per-person "It's a match" moment (SWIPE_IMPLEMENTATION_SPEC "Match moment"). The server
 * decides what is unseen, so the moment shows exactly once per person per match across devices;
 * clients mark it seen when they show it (or when the person turned match alerts off).
 */
class MatchCelebrationService(
    private val repository: MatchCelebrationRepository,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun listUncelebrated(viewerUserId: UUID): MatchCelebrationsResponseDto {
        val since = clock.instant().minus(CELEBRATION_WINDOW)
        val records = repository.listUncelebrated(viewerUserId, since, MAX_UNCELEBRATED)
        val viewerPhotoUrl = repository.firstProfilePhoto(viewerUserId)?.let { mediaAssetUrlResolver?.publicContentUrl(it) }
        if (records.isEmpty()) return MatchCelebrationsResponseDto(viewerPhotoUrl = viewerPhotoUrl)
        val byOther = records.associateBy { it.otherUserId }
        // A Psync together match is two canonical matches for the target (one per partner): one moment.
        val pairs: Map<UUID, UncelebratedMatchRecord?> =
            if (records.size < 2) {
                emptyMap()
            } else {
                buildMap<UUID, UncelebratedMatchRecord?> {
                    for (partners in repository.togetherSessionPartners(viewerUserId, since)) {
                        val (first, second) = partners.toList()
                        val a = byOther[first] ?: continue
                        val b = byOther[second] ?: continue
                        if (a.otherUserId in this || b.otherUserId in this) continue
                        val (lead, pair) = if (a.createdAt <= b.createdAt) a to b else b to a
                        put(lead.otherUserId, pair)
                        put(pair.otherUserId, null)
                    }
                }
            }
        val items =
            records.mapNotNull { record ->
                // The trailing half of a pair is folded into its lead's moment.
                if (pairs.containsKey(record.otherUserId) && pairs[record.otherUserId] == null) return@mapNotNull null
                val pair = pairs[record.otherUserId]
                MatchCelebrationDto(
                    matchId = record.matchId.toString(),
                    matchedUserId = record.otherUserId.toString(),
                    matchedDisplayName = record.otherDisplayName,
                    matchedPhotoUrl = record.otherPhotoMediaAssetId?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
                    viewerPhotoUrl = viewerPhotoUrl,
                    pairMatchId = pair?.matchId?.toString(),
                    pairUserId = pair?.otherUserId?.toString(),
                    pairDisplayName = pair?.otherDisplayName,
                    pairPhotoUrl = pair?.otherPhotoMediaAssetId?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
                    createdAt = record.createdAt.toString(),
                )
            }
        return MatchCelebrationsResponseDto(items = items, viewerPhotoUrl = viewerPhotoUrl)
    }

    suspend fun markCelebrated(
        viewerUserId: UUID,
        request: MarkMatchesCelebratedRequestDto,
    ): MarkMatchesCelebratedResponseDto {
        val ids =
            request.matchIds
                .asSequence()
                .mapNotNull { raw -> runCatching { UUID.fromString(raw.trim()) }.getOrNull() }
                .distinct()
                .take(MAX_MARK_PER_REQUEST)
                .toList()
        return MarkMatchesCelebratedResponseDto(marked = repository.markCelebrated(viewerUserId, ids))
    }

    companion object {
        /** Older unseen matches are left alone: a moment weeks late is noise, not a celebration. */
        val CELEBRATION_WINDOW: Duration = Duration.ofDays(14)
        const val MAX_UNCELEBRATED = 10
        const val MAX_MARK_PER_REQUEST = 20
    }
}
