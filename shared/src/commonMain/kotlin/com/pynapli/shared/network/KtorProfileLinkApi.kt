package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CreateProfileLinkInvitationRequestDto
import com.pynapli.shared.network.dto.ProfileLinksSummaryDto
import io.ktor.client.request.get
import io.ktor.http.HttpMethod

/** Ktor implementation of [ProfileLinkApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorProfileLinkApi(
    private val support: KtorApiSupport,
) : ProfileLinkApi {
    override suspend fun listProfileLinks(): AppResult<ProfileLinksSummaryDto> = support.authGet("/v1/profile-links")

    override suspend fun createProfileLinkInvitation(
        request: CreateProfileLinkInvitationRequestDto,
    ): AppResult<ProfileLinksSummaryDto> = support.authSend(HttpMethod.Post, "/v1/profile-links/invitations", request)

    override suspend fun acceptProfileLinkInvitation(linkId: String): AppResult<ProfileLinksSummaryDto> = support.authPost("/v1/profile-links/invitations/$linkId/accept")

    override suspend fun declineProfileLinkInvitation(linkId: String): AppResult<ProfileLinksSummaryDto> = support.authPost("/v1/profile-links/invitations/$linkId/decline")

    override suspend fun unlinkCurrentProfileLink(): AppResult<ProfileLinksSummaryDto> = support.authDelete("/v1/profile-links/current")
}
