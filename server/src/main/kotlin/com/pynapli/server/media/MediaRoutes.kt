package com.pynapli.server.media

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import com.pynapli.shared.network.dto.MEDIA_VIDEO_MAX_BYTES
import com.pynapli.shared.network.dto.ReorderMediaAlbumRequestDto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureMediaRoutes(
    authService: AuthService?,
    mediaService: MediaService?,
    localUploadStorage: LocalMediaUploadStorage? = null,
) {
    if (authService == null || mediaService == null) return

    routing {
        route("/v1/media/uploads") {
            localUploadStorage?.let { storage ->
                get("/local/{assetId}") {
                    val assetId = call.parameters["assetId"] ?: throw MediaException("MEDIA_ASSET_NOT_FOUND")
                    val content = storage.loadUpload(assetId)
                    call.respondBytes(
                        bytes = content.bytes,
                        contentType = ContentType.parse(content.contentType),
                    )
                }

                put("/local/{assetId}") {
                    val assetId = call.parameters["assetId"] ?: throw MediaException("MEDIA_ASSET_NOT_FOUND")
                    call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { contentLength ->
                        if (contentLength > MEDIA_VIDEO_MAX_BYTES) {
                            throw MediaException("MEDIA_FILE_TOO_LARGE")
                        }
                    }
                    val bytes = call.receive<ByteArray>()
                    storage.saveUpload(
                        mediaAssetIdText = assetId,
                        contentType = call.request.headers[HttpHeaders.ContentType],
                        bytes = bytes,
                    )
                    call.respond(HttpStatusCode.OK)
                }
            }

            post {
                val user = authService.authenticate(call.requiredBearerToken())
                val request = call.receive<CreateMediaUploadRequestDto>()
                call.respond(mediaService.createUploadSlot(user.id, request))
            }

            post("/{assetId}/complete") {
                val user = authService.authenticate(call.requiredBearerToken())
                val assetId = call.parameters["assetId"] ?: throw MediaException("MEDIA_ASSET_NOT_FOUND")
                val request = call.receive<CompleteMediaUploadRequestDto>()
                call.respond(mediaService.completeUpload(user.id, assetId, request))
            }
        }

        delete("/v1/media/assets/{assetId}") {
            val user = authService.authenticate(call.requiredBearerToken())
            val assetId = call.parameters["assetId"] ?: throw MediaException("MEDIA_ASSET_NOT_FOUND")
            mediaService.deleteAsset(user.id, assetId)
            call.respond(HttpStatusCode.OK)
        }

        route("/v1/media/albums") {
            get("/{kind}") {
                val user = authService.authenticate(call.requiredBearerToken())
                val kind = call.parameters["kind"] ?: throw MediaException("INVALID_MEDIA_ALBUM_KIND")
                call.respond(mediaService.listOwnAlbum(user.id, kind))
            }

            put("/{kind}/order") {
                val user = authService.authenticate(call.requiredBearerToken())
                val kind = call.parameters["kind"] ?: throw MediaException("INVALID_MEDIA_ALBUM_KIND")
                val request = call.receive<ReorderMediaAlbumRequestDto>()
                call.respond(mediaService.reorderAlbum(user.id, kind, request))
            }

            get("/private/grants") {
                val user = authService.authenticate(call.requiredBearerToken())
                call.respond(mediaService.listOwnPrivateAlbumGrants(user.id))
            }

            post("/private/grants/{granteeUserId}/revoke") {
                val user = authService.authenticate(call.requiredBearerToken())
                val granteeUserId = call.parameters["granteeUserId"] ?: throw MediaException("INVALID_ID")
                call.respond(mediaService.revokePrivateAlbumAccess(user.id, granteeUserId))
            }
        }

        get("/v1/media/users/{ownerUserId}/albums/private") {
            val user = authService.authenticate(call.requiredBearerToken())
            val ownerUserId = call.parameters["ownerUserId"] ?: throw MediaException("INVALID_ID")
            call.respond(mediaService.listPrivateAlbumForViewer(user.id, ownerUserId))
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
