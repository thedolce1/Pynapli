package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CreateProfileLinkInvitationRequestDto
import com.pynapli.shared.network.dto.ProfileLinksSummaryDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Ktor implementation of [ProfileLinkApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorProfileLinkApi(
    private val support: KtorApiSupport,
) : ProfileLinkApi {
    override suspend fun listProfileLinks(): AppResult<ProfileLinksSummaryDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/profile-links")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun createProfileLinkInvitation(
        request: CreateProfileLinkInvitationRequestDto,
    ): AppResult<ProfileLinksSummaryDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/profile-links/invitations")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun acceptProfileLinkInvitation(linkId: String): AppResult<ProfileLinksSummaryDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/profile-links/invitations/$linkId/accept")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun declineProfileLinkInvitation(linkId: String): AppResult<ProfileLinksSummaryDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/profile-links/invitations/$linkId/decline")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun unlinkCurrentProfileLink(): AppResult<ProfileLinksSummaryDto> =
        support.authenticated { token ->
            support.httpClient
                .delete(support.endpoint("/v1/profile-links/current")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }
}
