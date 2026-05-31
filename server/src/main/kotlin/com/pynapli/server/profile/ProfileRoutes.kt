package com.pynapli.server.profile

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.server.profiling.RequestProfilerFactory
import com.pynapli.server.profiling.profilerContextElement
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.routing
import kotlinx.coroutines.withContext
import java.util.UUID

fun Application.configureProfileRoutes(
    authService: AuthService?,
    profileService: ProfileService?,
    requestProfilerFactory: RequestProfilerFactory = RequestProfilerFactory.disabled(),
) {
    if (authService == null || profileService == null) return

    routing {
        get("/v1/me") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(profileService.getMe(user.id))
        }

        patch("/v1/me/profile") {
            val user = authService.authenticate(call.requiredBearerToken())
            val request = call.receive<UpdateProfileRequestDto>()
            call.respond(profileService.updateProfile(user.id, request))
        }

        patch("/v1/me/discovery-settings") {
            val user = authService.authenticate(call.requiredBearerToken())
            val request = call.receive<UpdateDiscoverySettingsRequestDto>()
            call.respond(profileService.updateDiscoverySettings(user.id, request))
        }

        get("/v1/profiles/{userId}") {
            val profiler = requestProfilerFactory.start(call)
            val response =
                withContext(profilerContextElement(profiler)) {
                    profiler.measure("profile.route.total") {
                        val user =
                            profiler.measure("profile.route.auth") {
                                authService.authenticate(call.requiredBearerToken())
                            }
                        val targetUserId =
                            profiler.measure("profile.route.params") {
                                call.parameters["userId"]?.toUuidOrNull()
                                    ?: throw ProfileException("PROFILE_NOT_FOUND")
                            }
                        profiler.measure("profile.service.total") {
                            profileService.getPublicProfile(user.id, targetUserId)
                        }
                    }
                }
            profiler.appendHeaders(call)
            call.respond(response)
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
