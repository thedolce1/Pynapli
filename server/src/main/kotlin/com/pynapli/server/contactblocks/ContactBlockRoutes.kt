package com.pynapli.server.contactblocks

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.shared.network.dto.ContactBlockAddressRequestDto
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
 * "Hide from people you know" (TRUST_AND_SAFETY_SPEC, "Contact blocks"). Token-derived, private to the
 * member. Add and remove answer 204 whatever the address is; there is no list, only the count.
 */
fun Application.configureContactBlockRoutes(
    authService: AuthService,
    contactBlockService: ContactBlockService,
) {
    routing {
        get("/v1/me/contact-blocks") {
            val user = call.authenticatedUser(authService)
            call.respond(contactBlockService.summary(user.id))
        }

        post("/v1/me/contact-blocks") {
            val user = call.authenticatedUser(authService)
            val request = call.receive<ContactBlockAddressRequestDto>()
            contactBlockService.add(user.id, request.email)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/v1/me/contact-blocks/remove") {
            val user = call.authenticatedUser(authService)
            val request = call.receive<ContactBlockAddressRequestDto>()
            contactBlockService.remove(user.id, request.email)
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/v1/me/contact-blocks") {
            val user = call.authenticatedUser(authService)
            contactBlockService.removeAll(user.id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
