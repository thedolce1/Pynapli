package com.pynapli.server.location

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * Passport / travel mode endpoints per API_CONTRACT_SPEC L163-169.
 * `GET` returns null (or a 204) when no active travel row exists so the client
 * can render the local-only state without an error branch.
 */
fun Application.configureTravelLocationRoutes(
    authService: AuthService,
    travelLocationService: TravelLocationService,
    travelPlaceSearchService: TravelPlaceSearchService,
    travelTripService: TravelTripService? = null,
) {
    routing {
        // Type-ahead place resolution for the free-text travel picker. Sub-minimum
        // queries return an empty list so the client needs no length special-casing.
        get("/v1/location/travel/search") {
            val user = call.authenticatedUser(authService)
            val query = call.request.queryParameters["q"].orEmpty()
            call.respond(travelPlaceSearchService.search(user.id, query))
        }

        get("/v1/location/travel") {
            val user = call.authenticatedUser(authService)
            val current = travelLocationService.getCurrent(user.id)
            if (current == null) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(current)
            }
        }

        post("/v1/location/travel") {
            val user = call.authenticatedUser(authService)
            val request = call.receive<SetTravelLocationRequestDto>()
            call.respond(travelLocationService.set(user.id, request))
        }

        delete("/v1/location/travel") {
            val user = call.authenticatedUser(authService)
            val cleared = travelLocationService.clear(user.id)
            if (cleared) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound)
        }

        // Planned trips (Select): Travel mode scheduled ahead, switched on and off by the server.
        if (travelTripService != null) {
            get("/v1/location/travel/trips") {
                val user = call.authenticatedUser(authService)
                call.respond(travelTripService.list(user.id))
            }

            post("/v1/location/travel/trips") {
                val user = call.authenticatedUser(authService)
                val request = call.receive<com.pynapli.shared.network.dto.PlanTravelTripRequestDto>()
                call.respond(travelTripService.plan(user.id, request))
            }

            delete("/v1/location/travel/trips/{tripId}") {
                val user = call.authenticatedUser(authService)
                val tripId =
                    call.parameters["tripId"]?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                        ?: throw TravelLocationException("TRIP_NOT_FOUND")
                travelTripService.cancel(user.id, tripId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
