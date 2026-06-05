package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CreateProfileLinkInvitationRequestDto
import com.pynapli.shared.network.dto.ProfileLinksSummaryDto

/**
 * Profile-links (partner linking) slice of the API surface, split out of the monolithic
 * [PynapliApiClient] (which now composes the per-feature interfaces). Covers listing links,
 * invitations (create/accept/decline), and unlinking. Signatures are unchanged.
 */
interface ProfileLinkApi {
    suspend fun listProfileLinks(): AppResult<ProfileLinksSummaryDto>

    suspend fun createProfileLinkInvitation(
        request: CreateProfileLinkInvitationRequestDto,
    ): AppResult<ProfileLinksSummaryDto>

    suspend fun acceptProfileLinkInvitation(linkId: String): AppResult<ProfileLinksSummaryDto>

    suspend fun declineProfileLinkInvitation(linkId: String): AppResult<ProfileLinksSummaryDto>

    suspend fun unlinkCurrentProfileLink(): AppResult<ProfileLinksSummaryDto>
}
